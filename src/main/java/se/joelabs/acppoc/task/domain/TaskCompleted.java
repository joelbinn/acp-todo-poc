package se.joelabs.acppoc.task.domain;

import java.time.Instant;

/** En öppen Uppgift slutfördes. */
public record TaskCompleted(long taskId, String title, Instant createdAt, Instant occurredAt) implements TaskChanged {

    @Override
    public TaskStatus status() {
        return TaskStatus.DONE;
    }
}
