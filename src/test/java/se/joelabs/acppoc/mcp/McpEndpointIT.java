package se.joelabs.acppoc.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Seam: /mcp över HTTP med MCP Java SDK-klient (FR-10, NFR-4, NFR-9, DR-1, DR-2, DR-6). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "poc.mcp.dev-token=dev-secret")
class McpEndpointIT {

    @LocalServerPort
    int port;

    @Autowired
    McpTokenService tokens;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger toolLog = (Logger) LoggerFactory.getLogger(ToolCallLog.class);

    @BeforeEach
    void captureToolLog() {
        toolLog.setLevel(Level.INFO);
        logs.start();
        toolLog.addAppender(logs);
    }

    @AfterEach
    void releaseToolLog() {
        toolLog.detachAppender(logs);
    }

    @Test
    void should_list_create_and_list_tools_when_client_has_valid_token() {
        var token = tokens.issue("chat-1");
        try (var client = client(token)) {
            assertThat(client.listTools().tools()).extracting(Tool::name).contains("create_task", "list_tasks", "rename_task");
        }
    }

    @Test
    void should_create_and_list_task_when_client_has_valid_token() {
        var token = tokens.issue("chat-2");
        try (var client = client(token)) {
            var created = client.callTool(new CallToolRequest("create_task", Map.of("title", "  Testa POC  ")));
            var listed = client.callTool(new CallToolRequest("list_tasks", Map.of()));

            assertThat(created.isError()).isNotEqualTo(true);
            assertThat(text(created)).contains("Testa POC");
            assertThat(text(listed)).contains("Testa POC").contains("OPEN");
        }
    }

    @Test
    void should_return_nothing_when_listing_done_tasks_and_none_completed() {
        var token = tokens.issue("chat-3");
        try (var client = client(token)) {
            client.callTool(new CallToolRequest("create_task", Map.of("title", "öppen")));

            var done = client.callTool(new CallToolRequest("list_tasks", Map.of("status", "DONE")));

            assertThat(text(done)).doesNotContain("öppen");
        }
    }

    @Test
    void should_return_readable_tool_error_when_title_invalid() {
        var token = tokens.issue("chat-4");
        try (var client = client(token)) {
            var result = client.callTool(new CallToolRequest("create_task", Map.of("title", "   ")));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("1–200").doesNotContain("Exception").doesNotContain("\tat ");
        }
    }

    @Test
    void should_complete_task_and_be_idempotent_when_completing_twice() {
        var token = tokens.issue("chat-c1");
        try (var client = client(token)) {
            client.callTool(new CallToolRequest("create_task", Map.of("title", "slutför mig")));
            var id = lastTaskId(client);

            var first = client.callTool(new CallToolRequest("complete_task", Map.of("id", id)));
            var second = client.callTool(new CallToolRequest("complete_task", Map.of("id", id)));

            assertThat(first.isError()).isNotEqualTo(true);
            assertThat(text(first)).contains("DONE");
            assertThat(second.isError()).isNotEqualTo(true);
        }
    }

    @Test
    void should_return_readable_tool_error_when_completing_or_deleting_unknown_id() {
        var token = tokens.issue("chat-c2");
        try (var client = client(token)) {
            for (var tool : java.util.List.of("complete_task", "delete_task")) {
                var result = client.callTool(new CallToolRequest(tool, Map.of("id", 987654)));

                assertThat(result.isError()).as(tool).isTrue();
                assertThat(text(result)).contains("987654").doesNotContain("Exception").doesNotContain("\tat ");
            }
        }
    }

    @Test
    void should_delete_task_and_annotate_delete_tool_as_destructive() {
        var token = tokens.issue("chat-c3");
        try (var client = client(token)) {
            client.callTool(new CallToolRequest("create_task", Map.of("title", "ta bort mig")));
            var id = lastTaskId(client);

            var result = client.callTool(new CallToolRequest("delete_task", Map.of("id", id)));

            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(text(client.callTool(new CallToolRequest("list_tasks", Map.of())))).doesNotContain("ta bort mig");
            var tools = client.listTools().tools();
            assertThat(tools).filteredOn(t -> t.name().equals("delete_task"))
                    .singleElement()
                    .satisfies(t -> assertThat(t.annotations().destructiveHint()).isTrue());
            assertThat(tools).filteredOn(t -> t.name().equals("complete_task")).hasSize(1);
        }
    }

    private static long lastTaskId(McpSyncClient client) {
        var listed = text(client.callTool(new CallToolRequest("list_tasks", Map.of())));
        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(listed);
        long id = -1;
        while (matcher.find()) {
            id = Long.parseLong(matcher.group(1));
        }
        return id;
    }

    @Test
    void should_respond_401_when_token_missing() throws Exception {
        assertThat(initializeStatus(null)).isEqualTo(401);
    }

    @Test
    void should_not_bypass_auth_when_path_is_not_normalized() throws Exception {
        for (var path : java.util.List.of("//mcp", "/mcp;x=1", "/mcp/", "/./mcp", "/%6Dcp")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            var status = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode();

            assertThat(status).as(path).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void should_accept_lowercase_bearer_scheme_when_token_valid() throws Exception {
        var token = tokens.issue("chat-case");

        assertThat(initializeStatus("bearer " + token)).isEqualTo(200);
    }

    @Test
    void should_respond_401_when_token_wrong() throws Exception {
        assertThat(initializeStatus("Bearer fel-token")).isEqualTo(401);
    }

    @Test
    void should_respond_401_when_token_revoked_after_chat_closed() throws Exception {
        var token = tokens.issue("chat-5");
        assertThat(initializeStatus("Bearer " + token)).isEqualTo(200);

        tokens.revoke(token);

        assertThat(initializeStatus("Bearer " + token)).isEqualTo(401);
    }

    @Test
    void should_accept_dev_token_when_configured() throws Exception {
        assertThat(initializeStatus("Bearer dev-secret")).isEqualTo(200);
    }

    @Test
    void should_log_tool_name_chat_id_and_duration_when_tool_called() {
        var token = tokens.issue("chat-log");
        try (var client = client(token)) {
            client.callTool(new CallToolRequest("list_tasks", Map.of()));
        }

        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(line -> assertThat(line)
                        .contains("list_tasks")
                        .contains("chat-log")
                        .containsPattern("\\d+ ?ms"));
    }

    @Test
    void should_log_dev_as_chat_id_when_dev_token_used() {
        try (var client = client("dev-secret")) {
            client.callTool(new CallToolRequest("list_tasks", Map.of()));
        }

        assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(line -> assertThat(line).contains("list_tasks").contains("dev"));
    }

    private McpSyncClient client(String token) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
        client.initialize();
        return client;
    }

    private int initializeStatus(String authorization) throws Exception {
        var body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"it","version":"0"}}}""";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
