package se.joelabs.acppoc.agent;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import se.joelabs.acppoc.events.AcpEventMapper;
import se.joelabs.acppoc.events.UiEvent;
import se.joelabs.acppoc.mcp.McpProperties;
import se.joelabs.acppoc.mcp.McpTokenService;

/**
 * Factory Method som skapar {@link AcpAgentSession}: en tom arbetskatalog (NFR-6), en MCP-token (NFR-4) och en
 * stdio-transport mot agentprocessen med minimal miljö (NFR-5). Städar token och katalog när sessionen stängs.
 */
@Component
public class AcpAgentSessionFactory implements IAgentSessionFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AcpAgentSessionFactory.class);

    private final AgentProperties agent;
    private final McpProperties mcp;
    private final McpTokenService tokens;
    private final AcpEventMapper mapper;

    public AcpAgentSessionFactory(
            AgentProperties agent, McpProperties mcp, McpTokenService tokens, AcpEventMapper mapper) {
        this.agent = agent;
        this.mcp = mcp;
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    public IAgentSession create(String chatId, Consumer<UiEvent> sink) {
        var token = tokens.issue(chatId);
        var workspace = createWorkspace(chatId);
        var parameters = AgentParameters.builder(agent.command())
                .args(agent.args())
                .env(environment())
                .inheritEnvironment(false)
                .build();
        var transport = new ProcessTrackingTransport(parameters);
        transport.setStdErrorHandler(line -> LOG.debug("[{}][agent stderr] {}", chatId, line));
        return new AcpAgentSession(chatId, transport, workspace, mcp.publicUrl(), token, agent, mapper, sink, () -> {
            transport.killIfAlive();
            tokens.revoke(token);
            deleteRecursively(workspace);
        });
    }

    private Path createWorkspace(String chatId) {
        try {
            return Files.createDirectories(agent.workspaceRoot().resolve(chatId));
        } catch (IOException e) {
            throw new UncheckedIOException("Kunde inte skapa arbetskatalog för " + chatId, e);
        }
    }

    /** Bara PATH, HOME och konfigurerade variabler når agentprocessen (NFR-5). Tomma värden hoppas över. */
    Map<String, String> environment() {
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

    private static void deleteRecursively(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            LOG.warn("Kunde inte ta bort arbetskatalogen {}", directory, e);
        }
    }
}
