package se.joelabs.acppoc.task.adapter.rest;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import se.joelabs.acppoc.task.application.TaskDto;
import se.joelabs.acppoc.task.application.TaskService;
import se.joelabs.acppoc.task.domain.InvalidTaskException;
import se.joelabs.acppoc.task.domain.TaskNotFoundException;

/** Driving adapter: panelens direkta ändringar (FR-16, FR-17) delegeras till {@link TaskService}. */
@RestController
@RequestMapping("/api/tasks")
class TaskController {

    private final TaskService tasks;

    TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    /** @param title ny titel; saknad titel ger samma fel som en tom */
    record TitleRequest(String title) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TaskDto create(@RequestBody TitleRequest request) {
        return tasks.create(request.title());
    }

    @PatchMapping("/{id}")
    TaskDto rename(@PathVariable long id, @RequestBody TitleRequest request) {
        return tasks.rename(id, request.title());
    }

    @PostMapping("/{id}/complete")
    TaskDto complete(@PathVariable long id) {
        return tasks.complete(id);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable long id) {
        tasks.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(InvalidTaskException.class)
    ResponseEntity<Map<String, String>> invalid(InvalidTaskException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TaskNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(TaskNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
