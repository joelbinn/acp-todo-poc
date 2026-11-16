package se.joelabs.acppoc.agent;

import com.agentclientprotocol.sdk.client.transport.AgentParameters;
import com.agentclientprotocol.sdk.client.transport.StdioAcpClientTransport;
import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Specialisering av SDK:ns stdio-transport som kan döda agentprocessen när {@code shutdown-grace} gått ut (FR-9).
 * SDK:n exponerar inte {@code Process}, så fältet {@code process} läses via reflektion. Det är acceptabelt eftersom
 * SDK-versionen är låst (0.81.0), och {@code FakeAgentProcessIT} faller om fältet byter namn vid uppgradering.
 */
class ProcessTrackingTransport extends StdioAcpClientTransport {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessTrackingTransport.class);
    private static final Field PROCESS_FIELD = processField();

    ProcessTrackingTransport(AgentParameters parameters) {
        super(parameters);
    }

    private static Field processField() {
        try {
            var field = StdioAcpClientTransport.class.getDeclaredField("process");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Kan inte nå SDK:ns agentprocess, shutdown-grace kan inte döda en envis process", e);
            return null;
        }
    }

    private Process process() {
        try {
            return PROCESS_FIELD == null ? null : (Process) PROCESS_FIELD.get(this);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /** Dödar agentprocessen och dess barnprocesser (t.ex. Node-adapterns kodagent) om de fortfarande lever. */
    void killIfAlive() {
        var process = process();
        if (process == null) {
            return;
        }
        var aliveAfterGrace = process.isAlive();
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (aliveAfterGrace) {
            LOG.warn("Agentprocessen lever efter shutdown-grace, dödas");
            process.destroyForcibly();
        }
    }
}
