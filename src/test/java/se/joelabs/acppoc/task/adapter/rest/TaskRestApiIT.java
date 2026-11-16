package se.joelabs.acppoc.task.adapter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import se.joelabs.acppoc.task.application.TaskDto;
import se.joelabs.acppoc.task.application.TaskService;
import se.joelabs.acppoc.task.domain.TaskStatus;

/** Seam: panelens REST-API över HTTP (FR-16, FR-17, NFR-10, DR-2, DR-5, DR-7). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskRestApiIT {

    private static final String ORIGIN = "http://localhost:8080";

    @LocalServerPort
    int port;

    @Autowired
    TaskService tasks;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void cleanUp() {
        tasks.list(null).forEach(task -> tasks.delete(task.id()));
    }

    @Test
    void should_create_task_and_return_201_when_title_valid() throws Exception {
        var response = send("POST", "/api/tasks", "{\"title\":\"  från panelen \"}", ORIGIN, "application/json");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"title\":\"från panelen\"").contains("\"status\":\"OPEN\"");
        assertThat(tasks.list(null)).extracting(TaskDto::title).containsExactly("från panelen");
    }

    @Test
    void should_return_400_with_readable_error_when_title_invalid() throws Exception {
        var response = send("POST", "/api/tasks", "{\"title\":\"   \"}", ORIGIN, "application/json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"error\"").contains("1–200");
        assertThat(tasks.list(null)).isEmpty();
    }

    @Test
    void should_rename_task_when_patching_title() throws Exception {
        var created = tasks.create("gammal");

        var response = send("PATCH", "/api/tasks/" + created.id(), "{\"title\":\"ny\"}", ORIGIN, "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"title\":\"ny\"");
        assertThat(tasks.list(null)).extracting(TaskDto::title).containsExactly("ny");
    }

    @Test
    void should_return_400_when_patch_has_invalid_title() throws Exception {
        var created = tasks.create("kvar");

        var response = send("PATCH", "/api/tasks/" + created.id(), "{\"title\":\"\"}", ORIGIN, "application/json");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(tasks.list(null)).extracting(TaskDto::title).containsExactly("kvar");
    }

    @Test
    void should_complete_task_when_posting_complete() throws Exception {
        var created = tasks.create("klar snart");

        var response = send("POST", "/api/tasks/" + created.id() + "/complete", "", ORIGIN, "application/json");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(tasks.list(TaskStatus.DONE)).extracting(TaskDto::id).containsExactly(created.id());
    }

    @Test
    void should_delete_task_and_return_204_when_deleting() throws Exception {
        var created = tasks.create("bort");

        var response = send("DELETE", "/api/tasks/" + created.id(), "", ORIGIN, "application/json");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(tasks.list(null)).isEmpty();
    }

    @Test
    void should_return_404_with_readable_error_when_id_unknown() throws Exception {
        for (var request : new String[][] {
            {"PATCH", "/api/tasks/999", "{\"title\":\"x\"}"},
            {"POST", "/api/tasks/999/complete", ""},
            {"DELETE", "/api/tasks/999", ""}
        }) {
            var response = send(request[0], request[1], request[2], ORIGIN, "application/json");

            assertThat(response.statusCode()).as(request[0] + " " + request[1]).isEqualTo(404);
            assertThat(response.body()).contains("\"error\"").contains("999").doesNotContain("Exception");
        }
    }

    @Test
    void should_return_403_and_change_nothing_when_origin_missing_or_foreign() throws Exception {
        var created = tasks.create("orörd");

        var foreign = send("DELETE", "/api/tasks/" + created.id(), "", "http://evil.example", "application/json");
        var missing = send("DELETE", "/api/tasks/" + created.id(), "", null, "application/json");

        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(tasks.list(null)).hasSize(1);
    }

    @Test
    void should_return_403_when_content_type_is_not_json() throws Exception {
        var response = send("POST", "/api/tasks", "title=x", ORIGIN, "application/x-www-form-urlencoded");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(tasks.list(null)).isEmpty();
    }

    private HttpResponse<String> send(String method, String path, String body, String origin, String contentType)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", contentType)
                .method(method, BodyPublishers.ofString(body));
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return http.send(builder.build(), BodyHandlers.ofString());
    }
}
