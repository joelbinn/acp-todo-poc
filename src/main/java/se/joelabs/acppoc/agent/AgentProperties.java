package se.joelabs.acppoc.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Konfiguration av den externa agenten (FR-12, ADR-0005).
 *
 * @param command agentkommando, t.ex. {@code claude-agent-acp}
 * @param args argument till kommandot
 * @param env variabler utöver {@code PATH} och {@code HOME} som agentprocessen får (NFR-5)
 * @param tools allt agenten får använda (FR-14)
 * @param preApprovedTools tools som körs utan Permission-förfrågan (FR-15)
 * @param workspaceRoot rotkatalog för tomma arbetskataloger, en per Chatt (NFR-6)
 * @param permissionTimeout hur länge en Permission-förfrågan väntar på användaren innan den räknas som avböjd (FR-5)
 * @param maxSessions högsta antal samtidiga Chattar (FR-9), standard 3
 * @param shutdownGrace hur länge en agentprocess får på sig att avsluta sig själv vid stängning innan den dödas
 *     (FR-9), standard 5 s
 */
@ConfigurationProperties("poc.agent")
public record AgentProperties(
        String command,
        List<String> args,
        Map<String, String> env,
        List<String> tools,
        List<String> preApprovedTools,
        Path workspaceRoot,
        Duration permissionTimeout,
        Integer maxSessions,
        Duration shutdownGrace) {

    static final Duration DEFAULT_PERMISSION_TIMEOUT = Duration.ofSeconds(120);
    static final int DEFAULT_MAX_SESSIONS = 3;
    static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /** Bekvämlighetskonstruktor med standardvärden för {@code maxSessions} och {@code shutdownGrace}. */
    public AgentProperties(
            String command,
            List<String> args,
            Map<String, String> env,
            List<String> tools,
            List<String> preApprovedTools,
            Path workspaceRoot,
            Duration permissionTimeout) {
        this(command, args, env, tools, preApprovedTools, workspaceRoot, permissionTimeout, null, null);
    }

    @ConstructorBinding
    public AgentProperties {
        maxSessions = maxSessions == null ? DEFAULT_MAX_SESSIONS : maxSessions;
        shutdownGrace = shutdownGrace == null ? DEFAULT_SHUTDOWN_GRACE : shutdownGrace;
        permissionTimeout = permissionTimeout == null ? DEFAULT_PERMISSION_TIMEOUT : permissionTimeout;
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        tools = tools == null ? List.of() : List.copyOf(tools);
        preApprovedTools = preApprovedTools == null ? List.of() : List.copyOf(preApprovedTools);
    }

    /** Skriver bara ut variabelnamn, aldrig värden, så att API-nycklar inte läcker till loggar (NFR-5). */
    @Override
    public String toString() {
        return "AgentProperties[command=" + command + ", args=" + args + ", envNames=" + env.keySet()
                + ", tools=" + tools + ", preApprovedTools=" + preApprovedTools + ", workspaceRoot=" + workspaceRoot
                + ", permissionTimeout=" + permissionTimeout + ", maxSessions=" + maxSessions
                + ", shutdownGrace=" + shutdownGrace + "]";
    }
}
