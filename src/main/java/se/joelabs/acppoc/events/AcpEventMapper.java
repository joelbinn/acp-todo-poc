package se.joelabs.acppoc.events;

import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.ContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.Plan;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter (GoF) mellan ACP:s {@code session/update} och frontendens {@link UiEvent}. Översätter ACP:s {@code session/update} till frontendens {@link UiEvent}. All ACP-kunskap i
 * händelseflödet stannar här. Okända varianter loggas på DEBUG och ignoreras.
 */
@Component
public class AcpEventMapper {

    static final int MAX_TOOL_RESULT_CHARS = 2000;

    private static final Logger LOG = LoggerFactory.getLogger(AcpEventMapper.class);

    /** @return motsvarande UI-händelse, eller tomt för uppdateringar som POC:n ignorerar */
    public Optional<UiEvent> map(SessionUpdate update) {
        return switch (update) {
            case AgentMessageChunk chunk -> text(chunk.content()).map(UiEvent.AgentText::new);
            case AgentThoughtChunk chunk -> text(chunk.content()).map(UiEvent.AgentThought::new);
            case ToolCall call -> Optional.of(new UiEvent.ToolCallStarted(
                    call.toolCallId(), call.title(), call.kind() == null ? null : call.kind().value(),
                    call.status() == null ? null : call.status().value()));
            case ToolCallUpdateNotification up -> Optional.of(new UiEvent.ToolCallUpdated(
                    up.toolCallId(), up.status() == null ? null : up.status().value(), resultText(up.content())));
            case Plan plan -> Optional.of(new UiEvent.PlanUpdated(plan.entries().stream()
                    .map(e -> new UiEvent.PlanItem(e.content(), e.status().value()))
                    .toList()));
            default -> ignored(update);
        };
    }

    /** @return {@code turn_end}-händelsen för svaret på {@code session/prompt} */
    public UiEvent turnEnd(StopReason stopReason) {
        return new UiEvent.TurnEnd(stopReason.value());
    }

    private static Optional<String> text(ContentBlock block) {
        return block instanceof TextContent text ? Optional.ofNullable(text.text()) : Optional.empty();
    }

    /** Slår ihop textinnehållet och trunkerar till {@value #MAX_TOOL_RESULT_CHARS} tecken. Tomt resultat ger {@code null}. */
    private static String resultText(List<ToolCallContent> content) {
        if (content == null) {
            return null;
        }
        var joined = content.stream()
                .filter(ToolCallContentBlock.class::isInstance)
                .map(c -> ((ToolCallContentBlock) c).content())
                .map(AcpEventMapper::text)
                .flatMap(Optional::stream)
                .filter(Objects::nonNull)
                .reduce((a, b) -> a + "\n" + b)
                .orElse(null);
        return joined == null || joined.length() <= MAX_TOOL_RESULT_CHARS
                ? joined
                : joined.substring(0, MAX_TOOL_RESULT_CHARS);
    }

    private static Optional<UiEvent> ignored(SessionUpdate update) {
        LOG.debug("Ignorerar session/update av typen {}", update.getClass().getSimpleName());
        return Optional.empty();
    }
}
