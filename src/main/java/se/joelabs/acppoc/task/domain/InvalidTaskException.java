package se.joelabs.acppoc.task.domain;

/** Domänregel överträdd, t.ex. ogiltig titel (DR-2). Meddelandet är läsbart för agent och användare. */
public class InvalidTaskException extends RuntimeException {

    public InvalidTaskException(String message) {
        super(message);
    }
}
