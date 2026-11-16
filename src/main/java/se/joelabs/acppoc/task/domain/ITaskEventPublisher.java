package se.joelabs.acppoc.task.domain;

/** Port: publicerar Uppgiftshändelser till de som lyssnar. */
public interface ITaskEventPublisher {

    void publish(TaskChanged event);
}
