package se.joelabs.acppoc.task.adapter.memory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;
import se.joelabs.acppoc.task.domain.ITaskRepository;
import se.joelabs.acppoc.task.domain.Task;

/** Driven adapter: Uppgiftslistan i minnet, sorterad på id. */
@Repository
public class InMemoryTaskRepository implements ITaskRepository {

    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, Task> tasks = new ConcurrentSkipListMap<>();

    @Override
    public long nextId() {
        return sequence.incrementAndGet();
    }

    @Override
    public void save(Task task) {
        tasks.put(task.id(), task);
    }

    @Override
    public Optional<Task> findById(long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public void delete(long id) {
        tasks.remove(id);
    }

    @Override
    public List<Task> findAll() {
        return List.copyOf(tasks.values());
    }
}
