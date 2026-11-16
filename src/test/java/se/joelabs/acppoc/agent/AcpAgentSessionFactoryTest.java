package se.joelabs.acppoc.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.joelabs.acppoc.events.AcpEventMapper;
import se.joelabs.acppoc.mcp.McpProperties;
import se.joelabs.acppoc.mcp.McpTokenService;

class AcpAgentSessionFactoryTest {

    @TempDir
    Path root;

    private final List<String> issued = new ArrayList<>();
    private final McpProperties mcp = new McpProperties("http://127.0.0.1:8080/mcp", "");
    private final McpTokenService tokens = new McpTokenService(mcp) {
        @Override
        public String issue(String chatId) {
            var token = super.issue(chatId);
            issued.add(token);
            return token;
        }
    };

    private AcpAgentSessionFactory factory(Map<String, String> env) {
        var props = new AgentProperties("agent", List.of(), env, List.of(), List.of(), root, null);
        return new AcpAgentSessionFactory(props, mcp, tokens, new AcpEventMapper());
    }

    @Test
    void should_create_empty_workspace_and_token_when_session_created() {
        factory(Map.of()).create("chat-1", e -> {});

        assertThat(root.resolve("chat-1")).isEmptyDirectory();
        assertThat(tokens.resolve(issued.getFirst())).contains("chat-1");
    }

    @Test
    void should_revoke_token_and_delete_workspace_when_session_closed() throws Exception {
        var session = factory(Map.of()).create("chat-1", e -> {});
        Files.writeString(root.resolve("chat-1").resolve("leftover.txt"), "x");

        session.close();

        assertThat(tokens.resolve(issued.getFirst())).isEmpty();
        assertThat(root.resolve("chat-1")).doesNotExist();
    }

    @Test
    void should_pass_only_path_home_and_configured_non_blank_variables_when_building_environment() {
        var env = factory(Map.of("ANTHROPIC_API_KEY", "sk-test", "EMPTY_ONE", " ")).environment();

        assertThat(env.keySet()).containsExactlyInAnyOrderElementsOf(
                withHomeAndPath("ANTHROPIC_API_KEY"));
        assertThat(env).containsEntry("ANTHROPIC_API_KEY", "sk-test").doesNotContainKey("EMPTY_ONE");
    }

    private static List<String> withHomeAndPath(String extra) {
        var names = new ArrayList<String>();
        for (var name : List.of("PATH", "HOME")) {
            if (System.getenv(name) != null) {
                names.add(name);
            }
        }
        names.add(extra);
        return names;
    }
}
