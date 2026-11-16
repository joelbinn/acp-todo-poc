package se.joelabs.acppoc.agent;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.AcpSyncClient;
import com.agentclientprotocol.sdk.spec.AcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema;
import com.agentclientprotocol.sdk.spec.AcpSchema.CancelNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.HttpHeader;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.joelabs.acppoc.events.AcpEventMapper;
import se.joelabs.acppoc.events.UiEvent;

/**
 * ACP-adapter bakom {@link IAgentSession}: en agentprocess (via given transport), en {@code AcpSyncClient}
 * och ett ACP-{@code sessionId}. En Tur körs på en virtuell tråd medan {@code session/update}-notiser
 * strömmar via {@code sessionUpdateHandler}.
 *
 * <p>Permission-förfrågningar besvaras aldrig av backend (NFR-7): de går till användaren som
 * {@code permission_request}, och svaret kommer via {@link #resolvePermission}. Uteblivet svar inom
 * {@code permissionTimeout}, avbruten Tur och stängd Chatt skickas som {@code cancelled}, vilket Agenten räknar
 * som avböjt.
 */
public class AcpAgentSession implements IAgentSession {

    private static final Logger LOG = LoggerFactory.getLogger(AcpAgentSession.class);
    private static final String MCP_SERVER_NAME = "poc-tools";
    private static final Duration START_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String PERMISSION_MODE = "default";
    private static final int MAX_DETAILS_CHARS = 500;

    private final String chatId;
    private final AcpClientTransport transport;
    private final Path workspace;
    private final String mcpUrl;
    private final String mcpToken;
    private final AgentProperties properties;
    private final AcpEventMapper mapper;
    private final Consumer<UiEvent> sink;
    private final Runnable onClose;
    private final AtomicBoolean turnRunning = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<String, PendingPermission> pendingPermissions = new ConcurrentHashMap<>();

    private volatile AcpSyncClient client;
    private volatile String sessionId;

    /**
     * @param transport transport mot agentprocessen (stdio i drift, in-memory i test)
     * @param onClose körs en gång när sessionen stängs, t.ex. för att återkalla token och ta bort arbetskatalogen
     */
    public AcpAgentSession(
            String chatId,
            AcpClientTransport transport,
            Path workspace,
            String mcpUrl,
            String mcpToken,
            AgentProperties properties,
            AcpEventMapper mapper,
            Consumer<UiEvent> sink,
            Runnable onClose) {
        this.chatId = chatId;
        this.transport = transport;
        this.workspace = workspace;
        this.mcpUrl = mcpUrl;
        this.mcpToken = mcpToken;
        this.properties = properties;
        this.mapper = mapper;
        this.sink = sink;
        this.onClose = onClose;
    }

    @Override
    public void start() {
        client = AcpClient.sync(transport)
                .requestTimeout(START_REQUEST_TIMEOUT) // initialize + session/new ryms inom 30 s (NFR-2)
                .promptTimeout(Duration.ofMinutes(10))
                .sessionUpdateHandler(n -> mapper.map(n.update()).ifPresent(sink))
                .requestPermissionHandler(this::awaitUserDecision)
                .build();
        var init = client.initialize();
        requireMcpHttp(init);
        var session = client.newSession(new NewSessionRequest(
                workspace.toString(),
                List.of(new McpServerHttp(
                        MCP_SERVER_NAME, mcpUrl, List.of(new HttpHeader("Authorization", "Bearer " + mcpToken)))),
                List.of(),
                sessionMeta()));
        sessionId = session.sessionId();
        selectDefaultPermissionMode(session);
        sink.accept(new UiEvent.SessionReady(
                chatId, agentName(init.agentInfo() == null ? null : init.agentInfo().name()), true));
    }

    /** Appens MCP-tools nås över HTTP. Utan stöd i agenten finns inget att chatta om (FR-11). */
    private void requireMcpHttp(AcpSchema.InitializeResponse init) {
        var mcp = init.agentCapabilities() == null ? null : init.agentCapabilities().mcpCapabilities();
        if (mcp == null || !Boolean.TRUE.equals(mcp.http())) {
            LOG.warn("[{}] Agenten annonserar inte mcpCapabilities.http, MCP-tools kan inte nås", chatId);
            throw new IllegalStateException(
                    "Agenten stöder inte HTTP-MCP, som appens tools kräver. Prova en annan agent (poc.agent.command).");
        }
    }

    /** Blockerar tills användaren svarat, tiden gått ut eller sessionen avbrutits. Körs på klientens tråd. */
    private RequestPermissionResponse awaitUserDecision(RequestPermissionRequest request) {
        var requestId = UUID.randomUUID().toString();
        var options = request.options() == null ? List.<com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption>of()
                : request.options();
        var pending = new PendingPermission(
                new CompletableFuture<>(),
                options.stream().map(com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption::optionId).collect(Collectors.toSet()));
        pendingPermissions.put(requestId, pending);
        try {
            if (closed.get()) {
                return cancelled();
            }
            sink.accept(new UiEvent.PermissionRequest(requestId, permissionTitle(request), toolKind(request), rawInput(request), options.stream()
                    .map(o -> new UiEvent.PermissionOption(
                            o.optionId(), o.name(), o.kind() == null ? null : o.kind().value()))
                    .toList()));
            var optionId = pending.answer().get(properties.permissionTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return optionId == null ? cancelled() : new RequestPermissionResponse(new PermissionSelected(optionId));
        } catch (TimeoutException e) {
            LOG.info("[{}] Permission-förfrågan {} fick inget svar i tid, räknas som avböjd", chatId, requestId);
            return cancelled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return cancelled();
        } catch (ExecutionException e) {
            return cancelled();
        } finally {
            pendingPermissions.remove(requestId);
        }
    }

    private static String permissionTitle(RequestPermissionRequest request) {
        var call = request.toolCall();
        var title = call == null ? null : Objects.requireNonNullElse(call.title(), call.name());
        return title == null || title.isBlank() ? "Agenten ber om tillåtelse" : title;
    }

    private static String toolKind(RequestPermissionRequest request) {
        var call = request.toolCall();
        return call == null || call.kind() == null ? null : call.kind().value();
    }

    /** Anropets råa indata, så att användaren ser vad som faktiskt körs och inte bara agentens beskrivning. */
    private static String rawInput(RequestPermissionRequest request) {
        var call = request.toolCall();
        if (call == null || call.rawInput() == null) {
            return null;
        }
        var text = String.valueOf(call.rawInput());
        return text.length() <= MAX_DETAILS_CHARS ? text : text.substring(0, MAX_DETAILS_CHARS);
    }

    private static RequestPermissionResponse cancelled() {
        return new RequestPermissionResponse(new PermissionCancelled());
    }

    @Override
    public void resolvePermission(String requestId, String optionId) {
        var pending = requestId == null ? null : pendingPermissions.get(requestId);
        if (pending == null) {
            LOG.debug("[{}] Svar på okänd Permission-förfrågan {}", chatId, requestId);
            return;
        }
        if (optionId != null && !pending.optionIds().contains(optionId)) {
            LOG.debug("[{}] Okänt alternativ {} för Permission-förfrågan {}", chatId, optionId, requestId);
            return;
        }
        pending.answer().complete(optionId);
    }

    /** Avbryter alla väntande förfrågningar (ACP kräver {@code cancelled} när en Tur avbryts). */
    private void cancelPendingPermissions() {
        pendingPermissions.values().forEach(p -> p.answer().complete(null));
    }

    private record PendingPermission(CompletableFuture<String> answer, Set<String> optionIds) {}

    /**
     * Adaptern startar i läget "auto" om inget annat sägs, där Claude Code själv godkänner tool-anrop. Läget
     * "default" ger en Permission-förfrågan för allt som inte är förgodkänt (ADR-0005). Det är konfiguration,
     * inte ett svar på en förfrågan.
     */
    private void selectDefaultPermissionMode(NewSessionResponse session) {
        var modes = session.modes();
        if (modes != null && modes.availableModes() != null
                && modes.availableModes().stream().anyMatch(m -> PERMISSION_MODE.equals(m.id()))) {
            client.setSessionMode(new SetSessionModeRequest(sessionId, PERMISSION_MODE));
        }
    }

    private String agentName(String reported) {
        return reported == null || reported.isBlank() ? properties.command() : reported;
    }

    private Map<String, Object> sessionMeta() {
        // settingSources=[] och strictMcpConfig stänger av användarens egna inställningar, skills och
        // MCP-servrar så att agenten bara ser POC:ns tools (FR-14).
        return Map.of("claudeCode", Map.of("options", Map.of(
                "tools", properties.tools(),
                "allowedTools", properties.preApprovedTools(),
                "settingSources", List.of(),
                "strictMcpConfig", true)));
    }

    @Override
    public void prompt(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sessionId == null) {
            sink.accept(new UiEvent.Error("Agenten är inte redo"));
            return;
        }
        if (!turnRunning.compareAndSet(false, true)) {
            sink.accept(new UiEvent.Error("En Tur pågår redan"));
            return;
        }
        Thread.ofVirtual().name("turn-" + chatId).start(() -> runTurn(text));
    }

    private void runTurn(String text) {
        try {
            var response = client.prompt(PromptRequest.text(sessionId, text));
            sink.accept(mapper.turnEnd(response.stopReason()));
        } catch (RuntimeException e) {
            if (closed.get()) {
                return; // Chatten stängdes under turen, ingen att rapportera till
            }
            LOG.warn("[{}] Turen misslyckades", chatId, e);
            sink.accept(new UiEvent.Error("Agenten svarade inte: " + e.getMessage()));
        } finally {
            turnRunning.set(false);
        }
    }

    @Override
    public void cancel() {
        cancelPendingPermissions();
        if (turnRunning.get() && client != null) {
            client.cancel(new CancelNotification(sessionId));
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            cancel();
            cancelPendingPermissions(); // en förfrågan som kom precis efter cancel()
            closeClientWithinGrace();
        } finally {
            onClose.run(); // dödar processen om den lever, återkallar token och tar bort arbetskatalogen
        }
    }

    /**
     * Stänger klienten (stdin stängs, agenten får avsluta sig själv) men väntar högst {@code shutdown-grace}. Hänger
     * agenten kvar dödar {@code onClose} processen, och då släpper även klientens stängning (FR-9).
     */
    private void closeClientWithinGrace() {
        var current = client;
        if (current == null) {
            return;
        }
        var closer = Thread.ofVirtual().name("close-" + chatId).start(current::close);
        try {
            if (!closer.join(properties.shutdownGrace())) {
                LOG.warn("[{}] Agenten stängde inte inom {}", chatId, properties.shutdownGrace());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
