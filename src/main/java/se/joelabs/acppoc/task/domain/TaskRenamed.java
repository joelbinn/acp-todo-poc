package se.joelabs.acppoc.task.domain;

import java.time.Instant;

/** En Uppgift bytte titel. @param previousTitle titeln före bytet @param status Uppgiftens Status vid bytet */
public record TaskRenamed(
        long taskId, String previousTitle, String title, TaskStatus status, Instant createdAt, Instant occurredAt)
        implements TaskChanged {}
