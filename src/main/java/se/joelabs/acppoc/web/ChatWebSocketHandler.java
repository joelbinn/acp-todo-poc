package se.joelabs.acppoc.web;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import se.joelabs.acppoc.agent.AgentSessionRegistry;
import se.joelabs.acppoc.agent.IAgentSession;
import se.joelabs.acppoc.agent.TooManyChatsException;
import se.joelabs.acppoc.events.UiEvent;
import se.joelabs.acppoc.task.application.TaskService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * En WebSocket = en Chatt = en Agentsession (FR-1). Klient → server: {@code prompt} och {@code cancel}.
 * {@code permission_response} besvarar en Permission-förfrågan. Server → klient: {@link UiEvent} som JSON.
 * Skrivningar synkroniseras ({@link ConcurrentWebSocketSessionDecorator}) eftersom agentströmmen och
 * uppgiftshändelser kan skriva samtidigt.
 */
@Component
class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final AgentSessionRegistry registry;
    private final TaskBroadcaster broadcaster;
    private final TaskService tasks;
    private final JsonMapper json;

    ChatWebSocketHandler(
            AgentSessionRegistry registry, TaskBroadcaster broadcaster, TaskService tasks, JsonMapper json) {
        this.registry = registry;
        this.broadcaster = broadcaster;
        this.tasks = tasks;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        var ws = new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        var chatId = UUID.randomUUID().toString();
        IAgentSession session;
        try {
            session = registry.open(raw.getId(), chatId, event -> send(ws, event));
        } catch (TooManyChatsException e) {
            LOG.info("Chatt avvisad: {}", e.getMessage());
            send(ws, new UiEvent.Error(e.getMessage()));
            closeQuietly(ws);
            return;
        }
        broadcaster.register(raw.getId(), event -> send(ws, event), () -> new UiEvent.TasksSnapshot(tasks.list(null)));
        // Agentstart tar sekunder och får inte blockera containerns tråd.
        Thread.ofVirtual().name("start-" + chatId).start(() -> startAgent(raw.getId(), ws, session));
    }

    private static void closeQuietly(WebSocketSession ws) {
        try {
            ws.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException e) {
            LOG.debug("Kunde inte stänga avvisad WebSocket", e);
        }
    }

    private void startAgent(String id, WebSocketSession ws, IAgentSession session) {
        try {
            session.start();
        } catch (RuntimeException e) {
            LOG.warn("Agenten kunde inte startas", e);
            send(ws, new UiEvent.Error("Agenten kunde inte startas: " + e.getMessage()));
            registry.close(id);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        JsonNode node;
        try {
            node = json.readTree(message.getPayload());
        } catch (RuntimeException e) {
            LOG.debug("Ogiltigt JSON från klient: {}", message.getPayload());
            return;
        }
        var type = node.path("type").asString("");
        registry.find(ws.getId()).ifPresent(session -> {
            switch (type) {
                case "prompt" -> session.prompt(node.path("text").asString(""));
                case "cancel" -> session.cancel();
                case "permission_response" -> session.resolvePermission(
                        node.path("requestId").asString(""), optionId(node));
                default -> LOG.debug("Okänd klientmeddelandetyp: {}", type);
            }
        });
    }

    /** {@code null} eller saknat {@code optionId} betyder att användaren avböjde. */
    private static String optionId(JsonNode node) {
        var option = node.path("optionId");
        return option.isString() ? option.asString() : null;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        broadcaster.unregister(ws.getId());
        registry.close(ws.getId());
    }

    private void send(WebSocketSession ws, UiEvent event) {
        try {
            if (ws.isOpen()) {
                ws.sendMessage(new TextMessage(json.writeValueAsString(event)));
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Kunde inte skicka {} till klienten", event, e);
        }
    }
}
