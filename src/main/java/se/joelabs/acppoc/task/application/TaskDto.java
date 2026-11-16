package se.joelabs.acppoc.task.application;

import java.time.Instant;
import se.joelabs.acppoc.task.domain.Task;
import se.joelabs.acppoc.task.domain.TaskChanged;
import se.joelabs.acppoc.task.domain.TaskStatus;

/** Läsmodell av en Uppgift som lämnar application-lagret (t.ex. till MCP-tools). */
public record TaskDto(long id, String title, TaskStatus status, Instant createdAt) {

    /** Uppgiftens tillstånd direkt efter att händelsen inträffade. */
    public static TaskDto from(TaskChanged event) {
        return new TaskDto(event.taskId(), event.title(), event.status(), event.createdAt());
    }

    static TaskDto from(Task task) {
        return new TaskDto(task.id(), task.title(), task.status(), task.createdAt());
    }
}
