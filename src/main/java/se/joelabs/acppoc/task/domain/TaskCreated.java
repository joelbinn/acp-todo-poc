package se.joelabs.acppoc.task.domain;

import java.time.Instant;

/** En Uppgift skapades (öppen). */
public record TaskCreated(long taskId, String title, Instant createdAt) implements TaskChanged {

    @Override
    public TaskStatus status() {
        return TaskStatus.OPEN;
    }

    @Override
    public Instant occurredAt() {
        return createdAt;
    }
}
