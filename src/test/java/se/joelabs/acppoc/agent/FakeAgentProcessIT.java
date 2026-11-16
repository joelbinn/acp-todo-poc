package se.joelabs.acppoc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import se.joelabs.acppoc.events.AcpEventMapper;
import se.joelabs.acppoc.events.UiEvent;
import se.joelabs.acppoc.mcp.McpProperties;
import se.joelabs.acppoc.mcp.McpTokenService;

/**
 * Processlivscykeln mot en riktig subprocess: en fake-agent ({@link FakeAgentMain}) byggd med SDK:ns agentstöd,
 * startad av den riktiga {@link AcpAgentSessionFactory} (FR-9, NFR-6).
 */
class FakeAgentProcessIT {

    private static final Duration NO_PROCESS_LEFT_WITHIN = Duration.ofSeconds(10);

    @TempDir
    Path root;

    private final List<UiEvent> events = new CopyOnWriteArrayList<>();
    private final McpProperties mcp = new McpProperties("http://127.0.0.1:8080/mcp", "");
    private final List<String> issuedTokens = new CopyOnWriteArrayList<>();
    private final McpTokenService tokens = new McpTokenService(mcp) {
        @Override
        public String issue(String chatId) {
            var token = super.issue(chatId);
            issuedTokens.add(token);
            return token;
        }
    };

    private IAgentSession newSession(String chatId) {
        return newSession(chatId, Duration.ofSeconds(2), null);
    }

    private IAgentSession newSession(String chatId, Duration shutdownGrace, String mode) {
        return newSession(chatId, shutdownGrace, mode, Map.of());
    }

    private IAgentSession newSession(String chatId, Duration shutdownGrace, String mode, Map<String, String> env) {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var props = new AgentProperties(
                java,
                agentArguments(mode),
                env, List.of(), List.of(), root, null, 3, shutdownGrace);
        return new AcpAgentSessionFactory(props, mcp, tokens, new AcpEventMapper()).create(chatId, events::add);
    }

    private static List<String> agentArguments(String mode) {
        var args = new java.util.ArrayList<>(List.of(
                "-Dlogback.configurationFile=logback-fake-agent.xml", "-cp", System.getProperty("java.class.path"),
                FakeAgentMain.class.getName()));
        if (mode != null) {
            args.add(mode);
        }
        return args;
    }

    private static Set<Long> childPids() {
        return ProcessHandle.current().children().map(ProcessHandle::pid).collect(Collectors.toSet());
    }

    @Test
    void should_start_stream_and_leave_no_process_when_chat_closed() {
        var before = childPids();
        var session = newSession("chat-1");

        session.start();
        session.prompt("hej");
        await().untilAsserted(() -> assertThat(events).contains(new UiEvent.TurnEnd("end_turn")));
        assertThat(events).contains(new UiEvent.AgentText("Hej "), new UiEvent.AgentText("där"));
        assertThat(childPids()).hasSizeGreaterThan(before.size());

        session.close();

        await().atMost(NO_PROCESS_LEFT_WITHIN).untilAsserted(() -> assertThat(childPids()).isEqualTo(before));
        assertThat(root.resolve("chat-1")).doesNotExist();
    }

    @Test
    void should_emit_error_and_leave_no_process_when_agent_process_dies_mid_turn() {
        var before = childPids();
        var session = newSession("chat-2");
        session.start();

        session.prompt("die");

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(events).anyMatch(e -> e instanceof UiEvent.Error));
        assertThat(events).noneMatch(e -> e instanceof UiEvent.TurnEnd);
        session.close();
        await().atMost(NO_PROCESS_LEFT_WITHIN).untilAsserted(() -> assertThat(childPids()).isEqualTo(before));
    }

    @Test
    void should_cancel_running_turn_and_leave_no_process_when_chat_closed_mid_turn() {
        var before = childPids();
        var session = newSession("chat-3");
        session.start();
        assertThat(tokens.resolve(issuedTokens.getFirst())).contains("chat-3");
        session.prompt("slow");
        await().untilAsserted(() -> assertThat(events).contains(new UiEvent.AgentThought("tänker")));

        var started = System.nanoTime();
        session.close();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(NO_PROCESS_LEFT_WITHIN);
        await().atMost(NO_PROCESS_LEFT_WITHIN).untilAsserted(() -> assertThat(childPids()).isEqualTo(before));
        assertThat(root.resolve("chat-3")).doesNotExist();
        assertThat(issuedTokens).hasSize(1);
        assertThat(tokens.resolve(issuedTokens.getFirst())).isEmpty();
    }

    @Test
    void should_kill_process_after_shutdown_grace_when_agent_does_not_exit_by_itself() {
        var before = childPids();
        var session = newSession("chat-4", Duration.ofMillis(500), "stubborn");
        session.start();

        var started = System.nanoTime();
        session.close();

        // Utan grace-döden skulle processen leva tills SDK:ns egen TERM efter 2 s
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(childPids()).isEqualTo(before));
    }

    @Test
    void should_never_log_api_key_when_session_runs_with_secret_in_environment() {
        var secret = "sk-ant-hemlig-nyckel-123";
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var previous = root.getLevel();
        root.setLevel(Level.TRACE);
        root.addAppender(appender);
        try {
            var session = newSession("chat-5", Duration.ofSeconds(2), null, Map.of("ANTHROPIC_API_KEY", secret));
            session.start();
            session.prompt("hej");
            await().untilAsserted(() -> assertThat(events).contains(new UiEvent.TurnEnd("end_turn")));
            session.close();
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }

        assertThat(appender.list).isNotEmpty().noneMatch(e -> e.getFormattedMessage().contains(secret)
                || String.valueOf(e.getThrowableProxy()).contains(secret));
    }
}
