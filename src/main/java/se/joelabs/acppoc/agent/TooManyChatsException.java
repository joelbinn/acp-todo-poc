package se.joelabs.acppoc.agent;

/** Det finns redan {@code max-sessions} öppna Chattar (FR-9). Meddelandet visas för användaren. */
public class TooManyChatsException extends RuntimeException {

    public TooManyChatsException(int max) {
        super("Max antal samtidiga chattar (" + max + ") är uppnått. Stäng en annan flik och försök igen.");
    }
}
