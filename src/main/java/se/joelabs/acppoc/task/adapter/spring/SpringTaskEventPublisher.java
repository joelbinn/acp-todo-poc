package se.joelabs.acppoc.task.adapter.spring;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import se.joelabs.acppoc.task.domain.ITaskEventPublisher;
import se.joelabs.acppoc.task.domain.TaskChanged;

/** Driven adapter: mappar porten mot Springs ApplicationEventPublisher. */
@Component
public class SpringTaskEventPublisher implements ITaskEventPublisher {

    private final ApplicationEventPublisher delegate;

    public SpringTaskEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(TaskChanged event) {
        delegate.publishEvent(event);
    }
}
