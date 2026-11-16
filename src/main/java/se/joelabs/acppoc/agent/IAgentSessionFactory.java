package se.joelabs.acppoc.agent;

import java.util.function.Consumer;
import se.joelabs.acppoc.events.UiEvent;

/** Factory Method för {@link IAgentSession}: skapar en ostartad Agentsession för en Chatt. */
@FunctionalInterface
public interface IAgentSessionFactory {

    /**
     * @param chatId Chattens id
     * @param sink tar emot alla {@link UiEvent} som sessionen producerar
     */
    IAgentSession create(String chatId, Consumer<UiEvent> sink);
}
