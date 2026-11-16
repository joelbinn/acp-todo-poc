package se.joelabs.acppoc.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSessionRegistryTest {

    private static final class RecordingSession implements IAgentSession {
        boolean closed;
        public void start() {}
        public void prompt(String text) {}
        public void resolvePermission(String requestId, String optionId) {}
        public void cancel() {}
        public void close() { closed = true; }
    }

    private final List<RecordingSession> created = new ArrayList<>();
    private static final AgentProperties TWO_CHATS = new AgentProperties(
            "unused", List.of(), Map.of(), List.of(), List.of(), Path.of("unused"), null, 2, Duration.ofSeconds(5));

    private final AgentSessionRegistry registry = new AgentSessionRegistry((chatId, sink) -> {
        var s = new RecordingSession();
        created.add(s);
        return s;
    }, TWO_CHATS);

    @Test
    void should_find_session_when_opened_for_web_socket() {
        var session = registry.open("ws-1", "chat-1", e -> {});

        assertThat(registry.find("ws-1")).containsSame(session);
    }

    @Test
    void should_close_and_forget_session_when_web_socket_closed() {
        registry.open("ws-1", "chat-1", e -> {});

        registry.close("ws-1");

        assertThat(created.getFirst().closed).isTrue();
        assertThat(registry.find("ws-1")).isEmpty();
    }

    @Test
    void should_close_all_sessions_when_application_shuts_down() {
        registry.open("ws-1", "chat-1", e -> {});
        registry.open("ws-2", "chat-2", e -> {});

        registry.closeAll();

        assertThat(created).allMatch(s -> s.closed);
    }

    @Test
    void should_reject_chat_with_clear_message_when_max_sessions_reached() {
        registry.open("ws-1", "chat-1", e -> {});
        registry.open("ws-2", "chat-2", e -> {});

        assertThatThrownBy(() -> registry.open("ws-3", "chat-3", e -> {}))
                .isInstanceOf(TooManyChatsException.class)
                .hasMessageContaining("2");
        assertThat(created).hasSize(2);
        assertThat(registry.find("ws-3")).isEmpty();
    }

    @Test
    void should_accept_new_chat_when_a_session_has_been_closed() {
        registry.open("ws-1", "chat-1", e -> {});
        registry.open("ws-2", "chat-2", e -> {});

        registry.close("ws-1");

        assertThat(registry.open("ws-3", "chat-3", e -> {})).isNotNull();
    }
}
