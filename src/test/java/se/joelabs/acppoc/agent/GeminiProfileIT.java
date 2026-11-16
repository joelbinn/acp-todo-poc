package se.joelabs.acppoc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Profilen {@code gemini} byter agent med enbart konfiguration (FR-12). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("gemini")
class GeminiProfileIT {

    @Autowired
    AgentProperties agent;

    @Test
    void should_start_gemini_cli_with_acp_flag_when_gemini_profile_active() {
        assertThat(agent.command()).isEqualTo("gemini");
        assertThat(agent.args()).containsExactly("--acp");
    }

    @Test
    void should_keep_limits_from_default_configuration_when_gemini_profile_active() {
        assertThat(agent.maxSessions()).isEqualTo(3);
        assertThat(agent.workspaceRoot()).isNotNull();
    }
}
