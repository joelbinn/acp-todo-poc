package se.joelabs.acppoc.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;
import se.joelabs.acppoc.task.application.TaskDto;

/**
 * Server → klient-händelser i WebSocket-protokollet. En {@code record} per {@code type} i POC-specens
 * protokolltabell. Frontend känner bara till dessa, aldrig till ACP.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(UiEvent.SessionReady.class),
    @JsonSubTypes.Type(UiEvent.AgentText.class),
    @JsonSubTypes.Type(UiEvent.AgentThought.class),
    @JsonSubTypes.Type(UiEvent.ToolCallStarted.class),
    @JsonSubTypes.Type(UiEvent.ToolCallUpdated.class),
    @JsonSubTypes.Type(UiEvent.PlanUpdated.class),
    @JsonSubTypes.Type(UiEvent.PermissionRequest.class),
    @JsonSubTypes.Type(UiEvent.TasksSnapshot.class),
    @JsonSubTypes.Type(UiEvent.TaskChange.class),
    @JsonSubTypes.Type(UiEvent.TurnEnd.class),
    @JsonSubTypes.Type(UiEvent.Error.class)
})
public sealed interface UiEvent {

    @JsonTypeName("session_ready")
    record SessionReady(String chatId, String agentName, boolean mcpHttpSupported) implements UiEvent {}

    @JsonTypeName("agent_text")
    record AgentText(String text) implements UiEvent {}

    @JsonTypeName("agent_thought")
    record AgentThought(String text) implements UiEvent {}

    @JsonTypeName("tool_call")
    record ToolCallStarted(String toolCallId, String title, String kind, String status) implements UiEvent {}

    @JsonTypeName("tool_call_update")
    record ToolCallUpdated(String toolCallId, String status, String content) implements UiEvent {}

    @JsonTypeName("plan")
    record PlanUpdated(List<PlanItem> entries) implements UiEvent {}

    @JsonTypeName("permission_request")
    /**
     * @param title agentens egen beskrivning (opålitlig text)
     * @param toolKind verktygstyp från agenten, t.ex. {@code delete}, eller {@code null}
     * @param details tool-anropets råa indata som JSON, trunkerad, eller {@code null}
     */
    record PermissionRequest(
            String requestId, String title, String toolKind, String details, List<PermissionOption> options)
            implements UiEvent {}

    /** Hela Uppgiftslistan, skickas när WebSocketen öppnas. Ersätter allt panelen visar. */
    @JsonTypeName("tasks_snapshot")
    record TasksSnapshot(List<TaskDto> tasks) implements UiEvent {}

    /** En Uppgiftshändelse, skickas till alla öppna flikar. */
    @JsonTypeName("task_changed")
    record TaskChange(Change change, TaskDto task) implements UiEvent {}

    @JsonTypeName("turn_end")
    record TurnEnd(String stopReason) implements UiEvent {}

    @JsonTypeName("error")
    record Error(String message) implements UiEvent {}

    enum Change {
        CREATED,
        UPDATED,
        DELETED
    }

    /** Ett alternativ i en Permission-förfrågan; {@code kind} är t.ex. {@code allow_once} eller {@code reject_once}. */
    record PermissionOption(String optionId, String name, String kind) {}

    /** En planpost: {@code status} är {@code pending}, {@code in_progress} eller {@code completed}. */
    record PlanItem(String content, String status) {}
}
