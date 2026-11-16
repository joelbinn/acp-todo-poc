package se.joelabs.acppoc.agent;

/**
 * Port mot en Agent: en Chatts Agentsession (ADR-0002, ADR-0004). Resten av appen ser bara det här
 * gränssnittet. Händelser levereras till den {@code Consumer<UiEvent>} som sessionen skapades med.
 */
public interface IAgentSession extends AutoCloseable {

    /** Startar agenten och skickar {@code session_ready}. Blockerar tills agenten är redo. */
    void start();

    /** Startar en Tur utan att blockera. Strömmen avslutas med {@code turn_end} eller {@code error}. */
    void prompt(String text);

    /**
     * Användarens svar på en Permission-förfrågan (FR-5).
     *
     * @param optionId valt alternativ, eller {@code null} för att avböja. Okänt {@code requestId} eller
     *     {@code optionId} ignoreras.
     */
    void resolvePermission(String requestId, String optionId);

    /** Avbryter en pågående Tur ({@code session/cancel}). Väntande Permission-förfrågningar avbryts. */
    void cancel();

    /** Avbryter en pågående Tur, stänger agenten och städar. Idempotent. */
    @Override
    void close();
}
