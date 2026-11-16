package se.joelabs.acppoc.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.joelabs.acppoc.task.adapter.memory.InMemoryTaskRepository;
import se.joelabs.acppoc.task.domain.ITaskEventPublisher;
import se.joelabs.acppoc.task.domain.InvalidTaskException;
import se.joelabs.acppoc.task.domain.TaskChanged;
import se.joelabs.acppoc.task.domain.TaskCompleted;
import se.joelabs.acppoc.task.domain.TaskCreated;
import se.joelabs.acppoc.task.domain.TaskDeleted;
import se.joelabs.acppoc.task.domain.TaskNotFoundException;
import se.joelabs.acppoc.task.domain.TaskStatus;

/** Seam: TaskService mot riktigt in-memory-repo och en inspelande publisher-port (DR-1, DR-2, DR-6). */
class TaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final TaskService service =
            new TaskService(new InMemoryTaskRepository(), publisher, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void should_assign_sequential_ids_when_creating_tasks() {
        var first = service.create("köpa mjölk");
        var second = service.create("ringa Anna");

        assertThat(first.id()).isEqualTo(1L);
        assertThat(second.id()).isEqualTo(2L);
    }

    @Test
    void should_create_open_task_with_creation_time_when_title_valid() {
        var task = service.create("köpa mjölk");

        assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        assertThat(task.createdAt()).isEqualTo(NOW);
        assertThat(task.title()).isEqualTo("köpa mjölk");
    }

    @Test
    void should_trim_title_when_it_has_surrounding_whitespace() {
        assertThat(service.create("  köpa mjölk \n").title()).isEqualTo("köpa mjölk");
    }

    @Test
    void should_accept_title_of_exactly_200_characters() {
        assertThat(service.create("a".repeat(200)).title()).hasSize(200);
    }

    @Test
    void should_reject_title_when_blank_after_trimming() {
        assertThatThrownBy(() -> service.create("   "))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("1–200");
    }

    @Test
    void should_reject_title_when_longer_than_200_characters() {
        assertThatThrownBy(() -> service.create("a".repeat(201)))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("1–200");
    }

    @Test
    void should_reject_title_when_null() {
        assertThatThrownBy(() -> service.create(null)).isInstanceOf(InvalidTaskException.class);
    }

    @Test
    void should_allow_duplicate_titles() {
        service.create("samma");
        service.create("samma");

        assertThat(service.list(null)).extracting(TaskDto::title).containsExactly("samma", "samma");
    }

    @Test
    void should_publish_exactly_one_task_created_when_creation_succeeds() {
        var task = service.create("köpa mjölk");

        assertThat(publisher.events).hasSize(1);
        assertThat(publisher.events.getFirst()).isInstanceOf(TaskCreated.class);
        assertThat(((TaskCreated) publisher.events.getFirst()).taskId()).isEqualTo(task.id());
    }

    @Test
    void should_publish_nothing_when_creation_fails() {
        assertThatThrownBy(() -> service.create(""));

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void should_list_all_tasks_in_creation_order_when_no_filter() {
        service.create("a");
        service.create("b");

        assertThat(service.list(null)).extracting(TaskDto::title).containsExactly("a", "b");
    }

    @Test
    void should_filter_by_status_when_status_given() {
        service.create("a");

        assertThat(service.list(TaskStatus.OPEN)).hasSize(1);
        assertThat(service.list(TaskStatus.DONE)).isEmpty();
    }

    @Test
    void should_complete_open_task_and_publish_task_completed_when_completing() {
        var created = service.create("köpa mjölk");
        publisher.events.clear();

        var done = service.complete(created.id());

        assertThat(done.status()).isEqualTo(TaskStatus.DONE);
        assertThat(service.list(TaskStatus.DONE)).extracting(TaskDto::id).containsExactly(created.id());
        assertThat(publisher.events).singleElement().isInstanceOf(TaskCompleted.class);
        assertThat(publisher.events.getFirst().taskId()).isEqualTo(created.id());
    }

    @Test
    void should_succeed_without_event_when_completing_already_completed_task() {
        var created = service.create("a");
        service.complete(created.id());
        publisher.events.clear();

        var again = service.complete(created.id());

        assertThat(again.status()).isEqualTo(TaskStatus.DONE);
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void should_fail_readably_and_publish_nothing_when_completing_unknown_id() {
        assertThatThrownBy(() -> service.complete(42))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining("42");

        assertThat(publisher.events).isEmpty();
    }

    @Test
    void should_remove_task_and_publish_task_deleted_when_deleting() {
        var created = service.create("a");
        publisher.events.clear();

        service.delete(created.id());

        assertThat(service.list(null)).isEmpty();
        assertThat(publisher.events).singleElement().isInstanceOf(TaskDeleted.class);
        assertThat(publisher.events.getFirst().title()).isEqualTo("a");
    }

    @Test
    void should_delete_completed_task_when_deleting() {
        var created = service.create("a");
        service.complete(created.id());

        service.delete(created.id());

        assertThat(service.list(null)).isEmpty();
    }

    @Test
    void should_fail_readably_when_deleting_unknown_id_or_twice() {
        var created = service.create("a");
        service.delete(created.id());

        assertThatThrownBy(() -> service.delete(created.id())).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> service.delete(99)).isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void should_publish_exactly_one_task_deleted_when_two_threads_delete_same_task() throws Exception {
        var created = service.create("a");
        publisher.events.clear();
        var failures = new java.util.concurrent.atomic.AtomicInteger();

        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try {
                        service.delete(created.id());
                    } catch (TaskNotFoundException e) {
                        failures.incrementAndGet();
                    }
                });
            }
        }

        assertThat(publisher.events).hasSize(1);
        assertThat(failures.get()).isEqualTo(1);
    }

    private static class RecordingPublisher implements ITaskEventPublisher {
        final List<TaskChanged> events = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public void publish(TaskChanged event) {
            events.add(event);
        }
    }
}
