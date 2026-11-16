package se.joelabs.acppoc.mcp;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Loggar varje tool-anrop med toolnamn, chatt-id och duration (NFR-9). */
@Component
public class ToolCallLog {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallLog.class);

    /** Kör anropet och loggar toolnamn, chatt-id och duration, även om anropet kastar. */
    public <T> T timed(String tool, Supplier<T> call) {
        var start = System.nanoTime();
        try {
            return call.get();
        } finally {
            var millis = (System.nanoTime() - start) / 1_000_000;
            LOG.info("MCP tool={} chatId={} duration={} ms", tool, currentChatId(), millis);
        }
    }

    private static String currentChatId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        var chatId = attributes.getAttribute(McpAuthFilter.CHAT_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return chatId == null ? "unknown" : chatId.toString();
    }
}
