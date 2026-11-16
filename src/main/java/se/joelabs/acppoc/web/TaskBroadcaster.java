package se.joelabs.acppoc.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import se.joelabs.acppoc.events.UiEvent;
import se.joelabs.acppoc.task.application.TaskDto;
import se.joelabs.acppoc.task.domain.TaskChanged;
import se.joelabs.acppoc.task.domain.TaskCompleted;
import se.joelabs.acppoc.task.domain.TaskCreated;
import se.joelabs.acppoc.task.domain.TaskDeleted;

/**
 * Observer (GoF): lyssnar på {@link TaskChanged} och skickar {@code task_changed} till alla öppna flikar, oavsett
 * vilken Chatt eller MCP-klient som gjorde ändringen (FR-13). Varje flik registreras med sin skrivsynkroniserade
 * sink, så att uppgiftshändelser och agentström aldrig skriver samtidigt till samma WebSocket.
 */
@Component
class TaskBroadcaster {

    private final Map<String, Consumer<UiEvent>> tabs = new ConcurrentHashMap<>();

    /**
     * Registrerar fliken och skickar snapshoten i samma lås som sändningen av händelser. En händelse kan då inte
     * hamna före en äldre snapshot och sedan skrivas över av den.
     */
    synchronized void register(String webSocketId, Consumer<UiEvent> sink, Supplier<UiEvent> snapshot) {
        tabs.put(webSocketId, sink);
        sink.accept(snapshot.get());
    }

    void unregister(String webSocketId) {
        tabs.remove(webSocketId);
    }

    @EventListener
    synchronized void on(TaskChanged event) {
        var message = new UiEvent.TaskChange(changeOf(event), TaskDto.from(event));
        tabs.values().forEach(sink -> sink.accept(message));
    }

    private static UiEvent.Change changeOf(TaskChanged event) {
        return switch (event) {
            case TaskCreated created -> UiEvent.Change.CREATED;
            case TaskCompleted completed -> UiEvent.Change.UPDATED;
            case TaskDeleted deleted -> UiEvent.Change.DELETED;
        };
    }
}
