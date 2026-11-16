package se.joelabs.acppoc.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AvailableCommandsUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.CurrentModeUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.Plan;
import com.agentclientprotocol.sdk.spec.AcpSchema.PlanEntry;
import com.agentclientprotocol.sdk.spec.AcpSchema.PlanEntryPriority;
import com.agentclientprotocol.sdk.spec.AcpSchema.PlanEntryStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.StopReason;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.UnknownSessionUpdate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AcpEventMapperTest {

    private final AcpEventMapper mapper = new AcpEventMapper();

    @Test
    void should_map_agent_message_chunk_to_agent_text_when_text_content() {
        var event = mapper.map(new AgentMessageChunk(new TextContent("Hej")));

        assertThat(event).contains(new UiEvent.AgentText("Hej"));
    }

    @Test
    void should_map_agent_thought_chunk_to_agent_thought_when_text_content() {
        var event = mapper.map(new AgentThoughtChunk(new TextContent("Funderar")));

        assertThat(event).contains(new UiEvent.AgentThought("Funderar"));
    }

    @Test
    void should_map_tool_call_to_tool_call_event_when_tool_call_started() {
        var update = new ToolCall("tool_call", "tc-1", "Skapar uppgift", null, ToolKind.OTHER,
                ToolCallStatus.PENDING, null, null, null, null, null);

        assertThat(mapper.map(update))
                .contains(new UiEvent.ToolCallStarted("tc-1", "Skapar uppgift", "other", "pending"));
    }

    @Test
    void should_map_tool_call_update_with_status_and_text_when_completed() {
        var update = toolCallUpdate("tc-1", ToolCallStatus.COMPLETED,
                List.of(new ToolCallContentBlock("content", new TextContent("klart"))));

        assertThat(mapper.map(update)).contains(new UiEvent.ToolCallUpdated("tc-1", "completed", "klart"));
    }

    @Test
    void should_truncate_tool_result_to_2000_characters_when_result_is_long() {
        var update = toolCallUpdate("tc-1", ToolCallStatus.COMPLETED,
                List.of(new ToolCallContentBlock("content", new TextContent("x".repeat(2500)))));

        var event = (UiEvent.ToolCallUpdated) mapper.map(update).orElseThrow();

        assertThat(event.content()).hasSize(2000);
    }

    @Test
    void should_map_tool_call_update_with_null_content_when_no_content_given() {
        var update = toolCallUpdate("tc-1", ToolCallStatus.FAILED, null);

        assertThat(mapper.map(update)).contains(new UiEvent.ToolCallUpdated("tc-1", "failed", null));
    }

    @Test
    void should_map_plan_to_plan_event_when_plan_updated() {
        var update = new Plan(List.of(
                new PlanEntry("Steg 1", PlanEntryPriority.HIGH, PlanEntryStatus.COMPLETED),
                new PlanEntry("Steg 2", PlanEntryPriority.MEDIUM, PlanEntryStatus.IN_PROGRESS)));

        assertThat(mapper.map(update)).contains(new UiEvent.PlanUpdated(List.of(
                new UiEvent.PlanItem("Steg 1", "completed"),
                new UiEvent.PlanItem("Steg 2", "in_progress"))));
    }

    @Test
    void should_ignore_update_when_variant_is_unknown_to_protocol_table() {
        assertThat(mapper.map(new UnknownSessionUpdate("brand_new", Map.of()))).isEmpty();
        assertThat(mapper.map(new CurrentModeUpdate("current_mode_update", "default", null))).isEmpty();
        assertThat(mapper.map(new AvailableCommandsUpdate("available_commands_update", List.of(), null))).isEmpty();
    }

    @Test
    void should_map_stop_reason_to_turn_end_when_prompt_returns() {
        assertThat(mapper.turnEnd(StopReason.CANCELLED)).isEqualTo(new UiEvent.TurnEnd("cancelled"));
    }

    private static ToolCallUpdateNotification toolCallUpdate(
            String id, ToolCallStatus status, List<ToolCallContent> content) {
        return new ToolCallUpdateNotification("tool_call_update", id, null, null, null, status, content, null, null, null, null);
    }
}
