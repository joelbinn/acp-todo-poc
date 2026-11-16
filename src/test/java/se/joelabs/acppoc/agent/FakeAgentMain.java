package se.joelabs.acppoc.agent;

import com.agentclientprotocol.sdk.agent.transport.StdioAcpAgentTransport;
import java.time.Duration;

/**
 * Fake-agent som egen process (stdio), byggd på SDK:ns agentstöd. Prompt med {@code die} dödar processen mitt i
 * Turen, {@code slow} håller Turen öppen. Argumentet {@code stubborn} gör att processen
 * inte avslutar sig själv när stdin stängs. Startas av {@link FakeAgentProcessIT} med testets classpath.
 */
public final class FakeAgentMain {

    private FakeAgentMain() {}

    private static void sleepLong() {
        try {
            Thread.sleep(Duration.ofMinutes(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        try (var agent = FakeAgent.crashingOnDie(new StdioAcpAgentTransport(), () -> Runtime.getRuntime().halt(3))) {
            agent.awaitTermination(); // returnerar när klienten stänger stdin
            if (args.length > 0 && "stubborn".equals(args[0])) {
                sleepLong(); // vägrar avsluta sig själv, så att klientens shutdown-grace måste döda processen
            }
        }
    }
}
