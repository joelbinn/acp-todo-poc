package se.joelabs.acppoc.agent;

import com.agentclientprotocol.sdk.client.AcpClient;
import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.HttpHeader;
import com.agentclientprotocol.sdk.spec.AcpSchema.McpServerHttp;
import com.agentclientprotocol.sdk.spec.AcpSchema.NewSessionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PromptRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SetSessionModeRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import java.nio.file.Files;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import se.joelabs.acppoc.mcp.McpProperties;
import se.joelabs.acppoc.mcp.McpTokenService;

/**
 * Spike (profil {@code spike}): bevisar att en riktig ACP-agent ansluter till appens HTTP-MCP-server,
 * att verktygsbegränsningen via {@code _meta.claudeCode.options} fungerar och att Permission-förfrågningar
 * uppstår för tools som inte är förgodkända (ADR-0003, ADR-0005).
 *
 * <p>Spiken godkänner aldrig en förfrågan själv (NFR-7): den loggar förfrågan och svarar {@code cancelled}.
 * Den här klassen är engångskod som ersätts av {@code AcpAgentSession} i ärende 02.
 */
@Component
@Profile("spike")
class AgentSpikeRunner implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AgentSpikeRunner.class);
    private static final String MCP_SERVER_NAME = "poc-tools";
    private static final String PERMISSION_MODE = "default";
    private static final List<String> PROMPTS = List.of(
            "Skapa en uppgift som heter Testa POC och lista sedan alla uppgifter",
            "Kör `ls` i skalet",
            "Lista exakt alla tool-namn du har tillgång till just nu, utan att anropa något av dem.");

    private final AgentProperties agent;
    private final McpProperties mcp;
    private final McpTokenService tokens;

    AgentSpikeRunner(AgentProperties agent, McpProperties mcp, McpTokenService tokens) {
        this.agent = agent;
        this.mcp = mcp;
        this.tokens = tokens;
    }

    @Override
    public void run(String... args) throws Exception {
        var chatId = "spike-" + UUID.randomUUID();
        var token = tokens.issue(chatId);
        var workspace = Files.createDirectories(agent.workspaceRoot().resolve(chatId));
        LOG.info("[spike] chatId={} cwd={} command={} {}", chatId, workspace, agent.command(), agent.args());
        LOG.info("[spike] förgodkända tools: {}", agent.preApprovedTools());

        var parameters = AgentParameters.builder(agent.command())
                .args(agent.args())
                .env(environment())
                .inheritEnvironment(false)
                .build();
        var transport = new StdioAcpClientTransport(parameters);
        transport.setStdErrorHandler(line -> LOG.debug("[spike][agent stderr] {}", line));

        try (var client = AcpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .promptTimeout(Duration.ofMinutes(3))
                .sessionUpdateHandler(n -> LOG.info("[spike] update: {}", summarize(n.update())))
                .requestPermissionHandler(request -> {
                    LOG.info("[spike] PERMISSION-FÖRFRÅGAN för {} med alternativ {} (svarar cancelled)",
                            request.toolCall(), request.options());
                    return new RequestPermissionResponse(new PermissionCancelled());
                })
                .build()) {
            var init = client.initialize();
            LOG.info("[spike] initialize: agentInfo={} mcpCapabilities={}",
                    init.agentInfo(), init.agentCapabilities().mcpCapabilities());

            var session = client.newSession(new NewSessionRequest(
                    workspace.toString(),
                    List.of(new McpServerHttp(
                            MCP_SERVER_NAME, mcp.publicUrl(), List.of(new HttpHeader("Authorization", "Bearer " + token)))),
                    List.of(),
                    sessionMeta()));
            LOG.info("[spike] session {} skapad, permission-läge={} tillgängliga={}",
                    session.sessionId(),
                    session.modes() == null ? null : session.modes().currentModeId(),
                    session.modes() == null ? null : session.modes().availableModes());
            // Utan defaultMode i inställningarna startar adaptern i läget "auto", där Claude Code själv
            // godkänner tool-anrop det bedömer som säkra. Vi sätter därför läget "default" så att allt som
            // inte är förgodkänt ger en Permission-förfrågan. Det är konfiguration, inte ett svar på en
            // förfrågan (ADR-0005, NFR-7).
            client.setSessionMode(new SetSessionModeRequest(session.sessionId(), PERMISSION_MODE));
            LOG.info("[spike] permission-läge satt till {}", PERMISSION_MODE);

            for (var prompt : PROMPTS) {
                LOG.info("[spike] PROMPT: {}", prompt);
                var response = client.prompt(PromptRequest.text(session.sessionId(), prompt));
                LOG.info("[spike] stopReason={}", response.stopReason());
            }
        } finally {
            tokens.revoke(token);
        }
    }

    /** Bara PATH, HOME och konfigurerade variabler når agentprocessen (NFR-5). Tomma värden hoppas över. */
    private Map<String, String> environment() {
        var env = new HashMap<String, String>();
        for (var name : List.of("PATH", "HOME")) {
            var value = System.getenv(name);
            if (value != null) {
                env.put(name, value);
            }
        }
        agent.env().forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                env.put(name, value);
            }
        });
        return env;
    }

    private Map<String, Object> sessionMeta() {
        // settingSources=[] och strictMcpConfig stänger av användarens egna inställningar, skills och
        // MCP-servrar så att agenten bara ser POC:ns tools (FR-14). Verifieras av den sista prompten.
        return Map.of("claudeCode", Map.of("options", Map.of(
                "tools", agent.tools(),
                "allowedTools", agent.preApprovedTools(),
                "settingSources", List.of(),
                "strictMcpConfig", true)));
    }

    private static String summarize(Object update) {
        if (update instanceof AgentMessageChunk chunk && chunk.content() instanceof TextContent text) {
            return "agent_message_chunk: " + text.text();
        }
        var text = update.toString();
        return text.length() > 400 ? text.substring(0, 400) + "…" : text;
    }
}
