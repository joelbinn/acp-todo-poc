package se.joelabs.acppoc.task.domain;

import java.time.Instant;

/** En Uppgift togs bort. @param status Uppgiftens Status när den togs bort */
public record TaskDeleted(long taskId, String title, TaskStatus status, Instant createdAt, Instant occurredAt)
        implements TaskChanged {}
