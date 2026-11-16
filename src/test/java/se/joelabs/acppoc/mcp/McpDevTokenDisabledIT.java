package se.joelabs.acppoc.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Seam: /mcp när POC_MCP_DEV_TOKEN inte är satt (NFR-4). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "poc.mcp.dev-token=")
class McpDevTokenDisabledIT {

    @LocalServerPort
    int port;

    @Test
    void should_respond_401_when_bearer_token_empty_and_dev_token_unset() throws Exception {
        assertThat(statusFor("Bearer ")).isEqualTo(401);
    }

    @Test
    void should_respond_401_when_dev_token_value_used_and_dev_token_unset() throws Exception {
        assertThat(statusFor("Bearer dev-secret")).isEqualTo(401);
    }

    private int statusFor(String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
