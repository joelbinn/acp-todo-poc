package se.joelabs.acppoc.task.domain;

import java.time.Instant;

/**
 * Uppgiftshändelse: ett faktum om att en Uppgift ändrats. Immutabel, namngiven i preteritum. Bär en kopia av
 * Uppgiftens tillstånd efter ändringen, så att lyssnare inte behöver slå upp den.
 */
public sealed interface TaskChanged permits TaskCreated, TaskCompleted, TaskDeleted {

    long taskId();

    String title();

    TaskStatus status();

    Instant createdAt();

    Instant occurredAt();
}
