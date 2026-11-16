package se.joelabs.acppoc.agent;

import com.agentclientprotocol.sdk.agent.AcpAgent;
import com.agentclientprotocol.sdk.agent.AcpSyncAgent;
import com.agentclientprotocol.sdk.spec.AcpAgentTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.CancelNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.Implementation;
import com.agentclientprotocol.sdk.spec.AcpSchema.InitializeResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptCapabilities;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Skriptad fake-agent över SDK:ns riktiga agent-sida (ingen mock av SDK:n, NFR-8). Beteende styrs av
 * promptens text: {@code slow} väntar tills turen avbryts, {@code permission} ställer en Permission-förfrågan och
 * svarar med utfallet som text, och {@code die} anropar {@code crash} mitt i turen. Annars ({@code outcome:selected:<id>} eller {@code outcome:cancelled}), annars streamas "Hej där" och turen avslutas.
 */
public final class FakeAgent implements AutoCloseable {

    public final List<NewSessionRequest> newSessionRequests = new CopyOnWriteArrayList<>();
    public final List<CancelNotification> cancellations = new CopyOnWriteArrayList<>();
    public final List<String> permissionOutcomes = new CopyOnWriteArrayList<>();
    private final AcpSyncAgent agent;

    private FakeAgent(AcpAgentTransport transport, boolean mcpHttp, Runnable crash) {
        this.agent = AcpAgent.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .initializeHandler(req -> new InitializeResponse(
                        req.protocolVersion(),
                        new AgentCapabilities(false, new McpCapabilities(mcpHttp, false), new PromptCapabilities()),
                        List.of(),
                        new Implementation("fake-agent", "1.0"),
                        null))
                .newSessionHandler(req -> {
                    newSessionRequests.add(req);
                    return new NewSessionResponse("fake-session");
                })
                .promptHandler((req, ctx) -> {
                    if (req.text().contains("die")) {
                        ctx.sendMessage("påväg att dö");
                        crash.run();
                    }
                    if (req.text().contains("slow")) {
                        ctx.sendThought("tänker");
                        for (int i = 0; i < 200 && !ctx.isCancelled(); i++) {
                            sleep(50);
                        }
                        return PromptResponse.cancelled();
                    }
                    if (req.text().contains("permission")) {
                        return askPermission(req.sessionId(), ctx);
                    }
                    ctx.sendMessage("Hej ");
                    ctx.sendMessage("där");
                    return PromptResponse.endTurn();
                })
                .cancelHandler(cancellations::add)
                .build();
        agent.start();
    }

    private PromptResponse askPermission(String sessionId, com.agentclientprotocol.sdk.agent.SyncPromptContext ctx) {
        var response = ctx.client().requestPermission(new RequestPermissionRequest(
                sessionId,
                new ToolCallUpdate("tc-1", "Ta bort uppgift 1", null, ToolKind.DELETE, ToolCallStatus.PENDING, null, null, java.util.Map.of("id", 1), null, null),
                List.of(
                        new PermissionOption("allow", "Tillåt", PermissionOptionKind.ALLOW_ONCE),
                        new PermissionOption("reject", "Neka", PermissionOptionKind.REJECT_ONCE))));
        var outcome = response.outcome() instanceof PermissionSelected selected
                ? "selected:" + selected.optionId()
                : "cancelled";
        permissionOutcomes.add(outcome);
        ctx.sendMessage("outcome:" + outcome);
        return PromptResponse.endTurn();
    }

    public static FakeAgent on(AcpAgentTransport transport) {
        return new FakeAgent(transport, true, () -> {});
    }

    /** Agent som inte annonserar {@code mcpCapabilities.http} (FR-11). */
    public static FakeAgent withoutMcpHttp(AcpAgentTransport transport) {
        return new FakeAgent(transport, false, () -> {});
    }

    /** Agent där en prompt med texten {@code die} kör {@code crash} mitt i Turen (t.ex. dödar processen). */
    public static FakeAgent crashingOnDie(AcpAgentTransport transport, Runnable crash) {
        return new FakeAgent(transport, true, crash);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Blockerar tills klienten stängt transporten (processläget). */
    public void awaitTermination() {
        agent.awaitTermination();
    }

    @Override
    public void close() {
        agent.close();
    }
}
