package se.joelabs.acppoc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.joelabs.acppoc.events.AcpEventMapper;
import se.joelabs.acppoc.events.UiEvent;

/** Testar {@link AcpAgentSession} mot SDK:ns in-memory-transport och en riktig SDK-agent (ingen mock). */
class AgentSessionTest {

    private static final AgentProperties PROPS = new AgentProperties(
            "unused", List.of(), Map.of(),
            List.of("mcp__poc-tools__*", "TodoWrite"),
            List.of("mcp__poc-tools__list_tasks"),
            Path.of("unused"),
            null);

    private final List<UiEvent> events = new CopyOnWriteArrayList<>();
    private InMemoryTransportPair pair;
    private FakeAgent agent;
    private AcpAgentSession session;
    private final AtomicBoolean cleaned = new AtomicBoolean();

    @BeforeEach
    void setUp() {
        pair = InMemoryTransportPair.create();
        agent = FakeAgent.on(pair.agentTransport());
        session = new AcpAgentSession(
                "chat-1", pair.clientTransport(), Path.of("/tmp/ws"), "http://127.0.0.1:8080/mcp", "tok-123",
                PROPS, new AcpEventMapper(), events::add, () -> cleaned.set(true));
    }

    @AfterEach
    void tearDown() {
        session.close();
        agent.close();
    }

    @Test
    void should_log_warning_and_fail_clearly_when_agent_does_not_advertise_mcp_http() {
        agent.close();
        pair = InMemoryTransportPair.create();
        agent = FakeAgent.withoutMcpHttp(pair.agentTransport());
        var noHttp = new AcpAgentSession(
                "chat-9", pair.clientTransport(), Path.of("/tmp/ws"), "http://127.0.0.1:8080/mcp", "tok-9",
                PROPS, new AcpEventMapper(), events::add, () -> {});
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AcpAgentSession.class);
        logger.addAppender(appender);
        try {
            assertThatThrownBy(noHttp::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HTTP-MCP");
        } finally {
            logger.detachAppender(appender);
            noHttp.close();
        }

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("mcpCapabilities.http"));
        assertThat(events).noneMatch(e -> e instanceof UiEvent.SessionReady);
    }

    @Test
    void should_emit_session_ready_when_started() {
        session.start();

        assertThat(events).containsExactly(new UiEvent.SessionReady("chat-1", "fake-agent", true));
    }

    @Test
    void should_send_claude_code_options_in_session_new_when_started() {
        session.start();

        var request = agent.newSessionRequests.getFirst();
        assertThat(request.cwd()).isEqualTo("/tmp/ws");
        assertThat(request.meta()).isEqualTo(Map.of("claudeCode", Map.of("options", Map.of(
                "tools", List.of("mcp__poc-tools__*", "TodoWrite"),
                "allowedTools", List.of("mcp__poc-tools__list_tasks"),
                "settingSources", List.of(),
                "strictMcpConfig", true))));
        assertThat(request.mcpServers()).hasSize(1);
    }

    @Test
    void should_stream_text_then_turn_end_in_order_when_prompted() {
        session.start();
        events.clear();

        session.prompt("hej");

        await().untilAsserted(() -> assertThat(events).contains(new UiEvent.TurnEnd("end_turn")));
        assertThat(events).containsExactly(
                new UiEvent.AgentText("Hej "), new UiEvent.AgentText("där"), new UiEvent.TurnEnd("end_turn"));
    }

    @Test
    void should_end_turn_with_cancelled_when_cancel_called_during_turn() {
        session.start();
        events.clear();

        session.prompt("slow");
        await().untilAsserted(() -> assertThat(events).contains(new UiEvent.AgentThought("tänker")));
        session.cancel();

        await().untilAsserted(() -> assertThat(events).contains(new UiEvent.TurnEnd("cancelled")));
        assertThat(agent.cancellations).hasSize(1);
    }

    @Test
    void should_emit_error_when_prompt_sent_during_running_turn() {
        session.start();
        events.clear();
        session.prompt("slow");
        await().untilAsserted(() -> assertThat(events).contains(new UiEvent.AgentThought("tänker")));

        session.prompt("en till");

        assertThat(events).anyMatch(e -> e instanceof UiEvent.Error);
        session.cancel();
    }

    @Test
    void should_emit_error_when_prompt_sent_before_session_started() {
        session.prompt("hej");

        assertThat(events).containsExactly(new UiEvent.Error("Agenten är inte redo"));
    }

    @Test
    void should_ignore_prompt_when_text_is_blank() {
        session.start();
        events.clear();

        session.prompt("   ");

        assertThat(events).isEmpty();
    }

    @Test
    void should_send_selected_option_to_agent_when_user_approves() {
        session.start();
        events.clear();

        session.prompt("permission");
        var request = awaitPermissionRequest();
        session.resolvePermission(request.requestId(), "allow");

        await().untilAsserted(() -> assertThat(events).contains(new UiEvent.TurnEnd("end_turn")));
        assertThat(agent.permissionOutcomes).containsExactly("selected:allow");
        assertThat(request.title()).isEqualTo("Ta bort uppgift 1");
        assertThat(request.toolKind()).isEqualTo("delete");
        assertThat(request.details()).contains("id").contains("1");
        assertThat(request.options()).extracting(UiEvent.PermissionOption::optionId).containsExactly("allow", "reject");
        assertThat(request.options()).extracting(UiEvent.PermissionOption::kind)
                .containsExactly("allow_once", "reject_once");
    }

    @Test
    void should_send_chosen_reject_option_to_agent_when_user_picks_reject() {
        session.start();
        session.prompt("permission");

        session.resolvePermission(awaitPermissionRequest().requestId(), "reject");

        await().untilAsserted(() -> assertThat(agent.permissionOutcomes).containsExactly("selected:reject"));
    }

    @Test
    void should_send_cancelled_to_agent_when_user_declines_with_null_option() {
        session.start();
        session.prompt("permission");

        session.resolvePermission(awaitPermissionRequest().requestId(), null);

        await().untilAsserted(() -> assertThat(agent.permissionOutcomes).containsExactly("cancelled"));
    }

    @Test
    void should_send_cancelled_to_agent_when_user_does_not_answer_within_timeout() {
        var shortTimeout = new AgentProperties(
                "unused", List.of(), Map.of(), List.of(), List.of(), Path.of("unused"), Duration.ofMillis(300));
        var impatient = new AcpAgentSession(
                "chat-2", pair.clientTransport(), Path.of("/tmp/ws"), "http://127.0.0.1:8080/mcp", "tok",
                shortTimeout, new AcpEventMapper(), events::add, () -> {});
        impatient.start();

        impatient.prompt("permission");

        await().untilAsserted(() -> assertThat(agent.permissionOutcomes).containsExactly("cancelled"));
        impatient.close();
        session = impatient;
    }

    @Test
    void should_send_cancelled_to_agent_when_session_closed_while_waiting() {
        session.start();
        session.prompt("permission");
        awaitPermissionRequest();

        session.close();

        await().untilAsserted(() -> assertThat(agent.permissionOutcomes).containsExactly("cancelled"));
    }

    @Test
    void should_ignore_answer_when_request_id_unknown() {
        session.start();
        session.prompt("permission");
        var request = awaitPermissionRequest();

        session.resolvePermission("finns-inte", "allow");
        session.resolvePermission(request.requestId(), "allow");

        await().untilAsserted(() -> assertThat(agent.permissionOutcomes).containsExactly("selected:allow"));
    }

    private UiEvent.PermissionRequest awaitPermissionRequest() {
        await().untilAsserted(() -> assertThat(events).anyMatch(e -> e instanceof UiEvent.PermissionRequest));
        return (UiEvent.PermissionRequest) events.stream()
                .filter(e -> e instanceof UiEvent.PermissionRequest).findFirst().orElseThrow();
    }

    @Test
    void should_run_cleanup_when_closed() {
        session.start();

        session.close();

        assertThat(cleaned).isTrue();
    }
}
