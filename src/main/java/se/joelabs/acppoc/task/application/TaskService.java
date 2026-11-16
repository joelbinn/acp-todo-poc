package se.joelabs.acppoc.task.application;

import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import se.joelabs.acppoc.task.domain.ITaskEventPublisher;
import se.joelabs.acppoc.task.domain.ITaskRepository;
import se.joelabs.acppoc.task.domain.Task;
import se.joelabs.acppoc.task.domain.TaskNotFoundException;
import se.joelabs.acppoc.task.domain.TaskStatus;

/** Tunn application service: delegerar till domänen och publicerar aggregatets händelser. */
@Service
public class TaskService {

    private final ITaskRepository repository;
    private final ITaskEventPublisher publisher;
    private final Clock clock;

    public TaskService(ITaskRepository repository, ITaskEventPublisher publisher, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    public TaskDto create(String title) {
        var task = Task.create(repository.nextId(), title, clock.instant());
        repository.save(task);
        publishEvents(task);
        return TaskDto.from(task);
    }

    /** Slutför en Uppgift. Redan slutförd ger ingen händelse (DR-3). */
    public synchronized TaskDto complete(long id) {
        var task = find(id);
        task.complete(clock.instant());
        publishEvents(task);
        return TaskDto.from(task);
    }

    /** Tar bort en Uppgift och returnerar den som den såg ut precis innan. */
    public synchronized TaskDto delete(long id) {
        var task = find(id);
        var dto = TaskDto.from(task);
        task.markDeleted(clock.instant());
        repository.delete(id);
        publishEvents(task);
        return dto;
    }

    private Task find(long id) {
        return repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    private void publishEvents(Task task) {
        task.pullEvents().forEach(publisher::publish);
    }

    /** @param status valfritt filter; {@code null} ger alla Uppgifter */
    public List<TaskDto> list(TaskStatus status) {
        return repository.findAll().stream()
                .filter(task -> status == null || task.status() == status)
                .map(TaskDto::from)
                .toList();
    }
}
