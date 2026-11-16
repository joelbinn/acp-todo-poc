package se.joelabs.acppoc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.agentclientprotocol.sdk.test.InMemoryTransportPair;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import se.joelabs.acppoc.agent.AcpAgentSession;
import se.joelabs.acppoc.agent.AgentProperties;
import se.joelabs.acppoc.agent.FakeAgent;
import se.joelabs.acppoc.agent.IAgentSessionFactory;
import se.joelabs.acppoc.events.AcpEventMapper;
import se.joelabs.acppoc.task.application.TaskService;
import tools.jackson.databind.json.JsonMapper;

/** WebSocket genom hela kedjan mot en fake-agent över SDK:ns in-memory-transport (NFR-8). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatWebSocketIT {

    @TestConfiguration
    static class FakeAgentConfig {
        @Bean
        @Primary
        IAgentSessionFactory fakeAgentSessionFactory() {
            return (chatId, sink) -> {
                var pair = InMemoryTransportPair.create();
                var agent = FakeAgent.on(pair.agentTransport());
                return new AcpAgentSession(
                        chatId, pair.clientTransport(), Path.of("/tmp/ws"), "http://127.0.0.1:8080/mcp", "tok",
                        new AgentProperties("fake", List.of(), Map.of(), List.of(), List.of(), Path.of("/tmp"), null),
                        new AcpEventMapper(), sink, agent::close);
            };
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @Autowired
    TaskService tasks;

    private WebSocketSession connect(String origin, List<String> received) throws Exception {
        var headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                        received.add(m.getPayload());
                    }
                }, headers, URI.create("ws://127.0.0.1:" + port + "/ws/chat"))
                .get();
    }

    private List<String> types(List<String> received) {
        return received.stream().map(m -> json.readTree(m).get("type").asString()).toList();
    }

    @Test
    void should_receive_session_ready_then_agent_text_then_turn_end_when_prompting_fake_agent() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (var ws = connect("http://localhost:8080", received)) {
            await().untilAsserted(() -> assertThat(types(received)).contains("session_ready"));

            ws.sendMessage(new TextMessage("{\"type\":\"prompt\",\"text\":\"hej\"}"));

            await().untilAsserted(() -> assertThat(types(received)).contains("turn_end"));
            assertThat(types(received)).containsExactly("tasks_snapshot", "session_ready", "agent_text", "agent_text", "turn_end");
            assertThat(json.readTree(received.get(1)).get("agentName").asString()).isEqualTo("fake-agent");
            assertThat(json.readTree(received.getLast()).get("stopReason").asString()).isEqualTo("end_turn");
        }
    }

    @Test
    void should_end_turn_with_cancelled_when_client_sends_cancel() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (var ws = connect("http://127.0.0.1:8080", received)) {
            await().untilAsserted(() -> assertThat(types(received)).contains("session_ready"));
            ws.sendMessage(new TextMessage("{\"type\":\"prompt\",\"text\":\"slow\"}"));
            await().untilAsserted(() -> assertThat(types(received)).contains("agent_thought"));

            ws.sendMessage(new TextMessage("{\"type\":\"cancel\"}"));

            await().untilAsserted(() -> assertThat(types(received)).contains("turn_end"));
            assertThat(json.readTree(received.getLast()).get("stopReason").asString()).isEqualTo("cancelled");
        }
    }

    private List<String> payloads(List<String> received, String type) {
        return received.stream().filter(m -> json.readTree(m).get("type").asString().equals(type)).toList();
    }

    @Test
    void should_send_tasks_snapshot_with_all_tasks_when_tab_opens() throws Exception {
        var existing = tasks.create("fanns redan");
        List<String> received = new CopyOnWriteArrayList<>();
        try (var ws = connect("http://localhost:8080", received)) {
            await().untilAsserted(() -> assertThat(payloads(received, "tasks_snapshot")).hasSize(1));

            var snapshot = json.readTree(payloads(received, "tasks_snapshot").getFirst());
            assertThat(snapshot.get("tasks").toString()).contains("\"id\":" + existing.id()).contains("fanns redan");
            assertThat(types(received).getFirst()).isEqualTo("tasks_snapshot");
        }
    }

    @Test
    void should_broadcast_task_changed_to_every_open_tab_when_tasks_change() throws Exception {
        List<String> first = new CopyOnWriteArrayList<>();
        List<String> second = new CopyOnWriteArrayList<>();
        try (var tab1 = connect("http://localhost:8080", first); var tab2 = connect("http://localhost:8080", second)) {
            await().untilAsserted(() -> {
                assertThat(payloads(first, "tasks_snapshot")).hasSize(1);
                assertThat(payloads(second, "tasks_snapshot")).hasSize(1);
            });

            var created = tasks.create("live");
            tasks.complete(created.id());
            tasks.delete(created.id());

            for (var received : List.of(first, second)) {
                await().untilAsserted(() -> assertThat(payloads(received, "task_changed")).hasSize(3));
                var changes = payloads(received, "task_changed").stream()
                        .map(m -> json.readTree(m).get("change").asString()).toList();
                assertThat(changes).containsExactly("CREATED", "UPDATED", "DELETED");
                var done = json.readTree(payloads(received, "task_changed").get(1)).get("task");
                assertThat(done.get("status").asString()).isEqualTo("DONE");
                assertThat(done.get("id").asLong()).isEqualTo(created.id());
            }
        }
    }

    @Test
    void should_stop_broadcasting_to_tab_when_it_is_closed() throws Exception {
        List<String> open = new CopyOnWriteArrayList<>();
        List<String> closed = new CopyOnWriteArrayList<>();
        try (var tab1 = connect("http://localhost:8080", open)) {
            var tab2 = connect("http://localhost:8080", closed);
            await().untilAsserted(() -> assertThat(payloads(closed, "tasks_snapshot")).hasSize(1));
            tab2.close();
            await().pollDelay(java.time.Duration.ofMillis(300)).until(() -> true);

            tasks.create("efter stängning");

            await().untilAsserted(() -> assertThat(payloads(open, "task_changed")).hasSize(1));
            assertThat(payloads(closed, "task_changed")).isEmpty();
        }
    }

    @Test
    void should_deliver_every_event_intact_when_tasks_change_concurrently_with_agent_stream() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (var ws = connect("http://localhost:8080", received)) {
            await().untilAsserted(() -> assertThat(types(received)).contains("session_ready"));
            ws.sendMessage(new TextMessage("{\"type\":\"prompt\",\"text\":\"hej\"}"));

            try (var pool = Executors.newFixedThreadPool(8)) {
                IntStream.range(0, 100).forEach(i -> pool.submit(() -> tasks.create("samtidig " + i)));
            }

            await().untilAsserted(() -> assertThat(payloads(received, "task_changed")).hasSize(100));
            await().untilAsserted(() -> assertThat(types(received)).contains("turn_end"));
            assertThat(payloads(received, "agent_text")).hasSize(2);
        } finally {
            // Håll listan liten: testklientens WebSocket har 8 KB meddelandegräns som en stor snapshot spräcker.
            tasks.list(null).stream().filter(t -> t.title().startsWith("samtidig ")).forEach(t -> tasks.delete(t.id()));
        }
    }

    @Test
    void should_relay_permission_request_and_user_choice_when_agent_asks() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (var ws = connect("http://localhost:8080", received)) {
            await().untilAsserted(() -> assertThat(types(received)).contains("session_ready"));

            ws.sendMessage(new TextMessage("{\"type\":\"prompt\",\"text\":\"permission\"}"));
            await().untilAsserted(() -> assertThat(payloads(received, "permission_request")).hasSize(1));
            var request = json.readTree(payloads(received, "permission_request").getFirst());
            assertThat(request.get("options")).hasSize(2);
            ws.sendMessage(new TextMessage("{\"type\":\"permission_response\",\"requestId\":\""
                    + request.get("requestId").asString() + "\",\"optionId\":\"allow\"}"));

            await().untilAsserted(() -> assertThat(types(received)).contains("turn_end"));
            assertThat(payloads(received, "agent_text")).singleElement().asString().contains("outcome:selected:allow");
        }
    }

    @Test
    void should_treat_null_option_as_declined_when_user_declines() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        try (var ws = connect("http://localhost:8080", received)) {
            await().untilAsserted(() -> assertThat(types(received)).contains("session_ready"));
            ws.sendMessage(new TextMessage("{\"type\":\"prompt\",\"text\":\"permission\"}"));
            await().untilAsserted(() -> assertThat(payloads(received, "permission_request")).hasSize(1));
            var id = json.readTree(payloads(received, "permission_request").getFirst()).get("requestId").asString();

            ws.sendMessage(new TextMessage("{\"type\":\"permission_response\",\"requestId\":\"" + id + "\",\"optionId\":null}"));

            await().untilAsserted(() -> assertThat(types(received)).contains("turn_end"));
            assertThat(payloads(received, "agent_text")).singleElement().asString().contains("outcome:cancelled");
        }
    }

    @Test
    void should_reject_handshake_when_origin_is_not_allowed() {
        assertThatThrownBy(() -> connect("http://evil.example.com", new ArrayList<>()))
                .hasStackTraceContaining("403");
    }

    @Test
    void should_send_error_and_close_when_a_chat_exceeds_max_sessions() throws Exception {
        var open = new ArrayList<WebSocketSession>();
        try {
            for (int i = 0; i < 3; i++) {
                var received = new CopyOnWriteArrayList<String>();
                open.add(connect("http://localhost:8080", received));
                await().untilAsserted(() -> assertThat(types(received)).contains("session_ready"));
            }
            var rejected = new CopyOnWriteArrayList<String>();

            var fourth = connect("http://localhost:8080", rejected);

            await().untilAsserted(() -> assertThat(types(rejected)).contains("error"));
            assertThat(rejected).anyMatch(m -> m.contains("Max antal samtidiga chattar"));
            assertThat(types(rejected)).doesNotContain("session_ready");
            await().until(() -> !fourth.isOpen());
        } finally {
            for (var ws : open) {
                ws.close();
            }
        }
    }
}
