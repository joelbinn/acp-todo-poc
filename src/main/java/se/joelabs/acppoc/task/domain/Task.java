package se.joelabs.acppoc.task.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Aggregate Root: en Uppgift (DR-1). Varje lyckad ändring ger en Uppgiftshändelse (DR-6). */
public final class Task {

    static final int MAX_TITLE_LENGTH = 200;

    private final long id;
    private String title;
    private TaskStatus status;
    private final Instant createdAt;
    private final List<TaskChanged> pendingEvents = new ArrayList<>();

    private Task(long id, String title, TaskStatus status, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** Skapar en öppen Uppgift. Titeln trimmas och måste sedan vara 1–200 tecken (DR-2). */
    public static Task create(long id, String rawTitle, Instant now) {
        var title = validTitle(rawTitle);
        var task = new Task(id, title, TaskStatus.OPEN, now);
        task.pendingEvents.add(new TaskCreated(id, title, now));
        return task;
    }

    /**
     * Byter titel oavsett Status (DR-7). Titeln valideras som vid skapande. Oförändrad titel efter trimning ger
     * ingen händelse.
     */
    public synchronized void rename(String rawTitle, Instant now) {
        var newTitle = validTitle(rawTitle);
        if (newTitle.equals(title)) {
            return;
        }
        var previous = title;
        title = newTitle;
        pendingEvents.add(new TaskRenamed(id, previous, newTitle, status, createdAt, now));
    }

    /**
     * Slutför Uppgiften. En redan slutförd Uppgift lämnas orörd utan händelse (DR-3). Det finns ingen väg
     * tillbaka till {@code OPEN} (DR-4).
     */
    public synchronized void complete(Instant now) {
        if (status == TaskStatus.DONE) {
            return;
        }
        status = TaskStatus.DONE;
        pendingEvents.add(new TaskCompleted(id, title, createdAt, now));
    }

    /** Markerar Uppgiften som borttagen. Repositoryt tar bort den ur samlingen. */
    public synchronized void markDeleted(Instant now) {
        pendingEvents.add(new TaskDeleted(id, title, status, createdAt, now));
    }

    private static String validTitle(String rawTitle) {
        var title = rawTitle == null ? "" : rawTitle.strip();
        if (title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
            throw new InvalidTaskException("Titeln måste vara 1–" + MAX_TITLE_LENGTH + " tecken efter trimning.");
        }
        return title;
    }

    /** Hämtar och tömmer händelser som aggregatet skapat sedan sist. */
    public synchronized List<TaskChanged> pullEvents() {
        var events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public long id() {
        return id;
    }

    public synchronized String title() {
        return title;
    }

    public synchronized TaskStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
