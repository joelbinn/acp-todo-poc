package se.joelabs.acppoc.task.domain;

import java.util.List;
import java.util.Optional;

/** Port: samlingsliknande åtkomst till Uppgifter. Delar ut löpnummer. Publicerar aldrig händelser. */
public interface ITaskRepository {

    long nextId();

    void save(Task task);

    Optional<Task> findById(long id);

    /** Tar bort Uppgiften. Okänt id ignoreras. */
    void delete(long id);

    List<Task> findAll();
}
