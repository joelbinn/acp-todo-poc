package se.joelabs.acppoc.task.domain;

/** Okänt id (DR-5). Meddelandet är läsbart för agent och användare. */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(long id) {
        super("Det finns ingen uppgift med id " + id + ".");
    }
}
