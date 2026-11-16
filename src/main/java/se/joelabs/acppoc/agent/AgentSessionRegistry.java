package se.joelabs.acppoc.agent;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import se.joelabs.acppoc.events.UiEvent;

/** Mappar WebSocket-session-id till Agentsession och stänger allt vid nedstängning av appen. */
@Component
public class AgentSessionRegistry {

    private final IAgentSessionFactory factory;
    private final int maxSessions;
    private final Map<String, IAgentSession> sessions = new ConcurrentHashMap<>();

    public AgentSessionRegistry(IAgentSessionFactory factory, AgentProperties properties) {
        this.factory = factory;
        this.maxSessions = properties.maxSessions();
    }

    /**
     * Skapar och registrerar en ostartad session; anroparen startar den.
     *
     * @throws TooManyChatsException när {@code max-sessions} redan är uppnått (FR-9)
     */
    public synchronized IAgentSession open(String webSocketId, String chatId, Consumer<UiEvent> sink) {
        if (sessions.size() >= maxSessions) {
            throw new TooManyChatsException(maxSessions);
        }
        var session = factory.create(chatId, sink);
        sessions.put(webSocketId, session);
        return session;
    }

    /** @return sessionen för WebSocketen, om den finns */
    public Optional<IAgentSession> find(String webSocketId) {
        return Optional.ofNullable(sessions.get(webSocketId));
    }

    /** Stänger och avregistrerar sessionen. Okänt id ignoreras. */
    public void close(String webSocketId) {
        Optional.ofNullable(sessions.remove(webSocketId)).ifPresent(IAgentSession::close);
    }

    @PreDestroy
    void closeAll() {
        sessions.keySet().forEach(this::close);
    }
}
