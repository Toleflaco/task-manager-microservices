package com.mtole.task.activity;

import com.mtole.task.categories.events.CategoryCreatedEvent;
import com.mtole.task.categories.events.CategoryDeletedEvent;
import com.mtole.task.categories.events.CategoryUpdatedEvent;
import com.mtole.task.tasks.events.TaskCreatedEvent;
import com.mtole.task.tasks.events.TaskDeletedEvent;
import com.mtole.task.tasks.events.TaskStatusChangedEvent;
import com.mtole.task.tasks.events.TaskUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Listener de eventos de dominio. Traduce cada evento publicado por
 * los services de PostgreSQL en un documento ActivityEvent que se
 * persiste en MongoDB.
 * <p>
 * Síncrono por defecto: los métodos se ejecutan en el mismo hilo y
 * dentro de la misma transacción que el publisher. Si la escritura
 * en MongoDB falla, se produce rollback de la transacción JPA
 * completa (la operación original en PostgreSQL también se revierte).
 * <p>
 * Si en el futuro hace falta desacoplar latencias (audit logging
 * lento bloqueando los endpoints), se puede pasar a asíncrono
 * añadiendo @Async + configurando un TaskExecutor, aceptando que el
 * audit log puede perderse si la escritura en Mongo falla.
 */
@Component
public class ActivityEventListener {

    private static final Logger log = LoggerFactory.getLogger(ActivityEventListener.class);

    private final ActivityEventRepository repository;

    public ActivityEventListener(ActivityEventRepository repository) {
        this.repository = repository;
    }

    @EventListener
    public void onTaskCreated(TaskCreatedEvent event) {
        log.debug("Recording TaskCreated event for taskId={}", event.taskId());

        Map<String, Object> after = new HashMap<>();
        after.put("title", event.title());
        after.put("status", event.status());
        if (event.categoryId() != null) {
            after.put("categoryId", event.categoryId());
        }

        ActivityEvent doc = new ActivityEvent(
                event.userId(),
                "TASK_CREATED",
                "TASK",
                event.taskId(),
                Map.of(),
                after,
                event.occurredAt()
        );
        repository.save(doc);
    }

    // TODO: implementar el resto de listeners:
    //   - onTaskUpdated(TaskUpdatedEvent)

    @EventListener
    public void onTaskUpdated(TaskUpdatedEvent event) {
        log.debug("Recording TaskUpdated event for taskId={}", event.taskId());

        ActivityEvent doc = new ActivityEvent(
                event.userId(),
                "TASK_UPDATED",
                "TASK",
                event.taskId(),
                Map.of(),
                Map.of(),
                event.occurredAt()
        );
        repository.save(doc);
    }

    @EventListener
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        log.debug("Recording TaskStatusChanged event for taskId={}", event.taskId());

        // before y after vacíos
        ActivityEvent doc = new ActivityEvent(
                event.userId(),
                "TASK_STATUS_CHANGED",
                "TASK",
                event.taskId(),
                Map.of("status", event.oldStatus()),
                Map.of("status", event.newStatus()),
                event.occurredAt()
        );
        repository.save(doc);
    }

    //   - onTaskDeleted(TaskDeletedEvent)
    @EventListener
    public void onTaskDeleted(TaskDeletedEvent event) {
        log.debug("Recording TaskDeleted event for taskId={}", event.taskId());

        Map<String, Object> before = new HashMap<>();
        before.put("title", event.title());
        before.put("status", event.status());

        ActivityEvent doc = new ActivityEvent(
                event.userId(),
                "TASK_DELETED",
                "TASK",
                event.taskId(),
                before,
                Map.of(),
                event.occurredAt()
        );
        repository.save(doc);
    }

    //   - onCategoryCreated(CategoryCreatedEvent)
    @EventListener
    public void onCategoryCreated(CategoryCreatedEvent event) {
        log.debug("Recording CategoryCreated event for categoryId={}", event.categoryId());

        Map<String, Object> after = new HashMap<>();
        after.put("name", event.name());

        ActivityEvent doc = new ActivityEvent(
                event.userId(),
                "CATEGORY_CREATED",
                "CATEGORY",
                event.categoryId(),
                Map.of(),
                after,
                event.occurredAt()
        );
        repository.save(doc);
    }

    //   - onCategoryUpdated(CategoryUpdatedEvent)
    @EventListener
    public void onCategoryUpdated(CategoryUpdatedEvent event) {
        log.debug("Recording CategoryUpdated event for categoryId={}", event.categoryId());
        ActivityEvent doc = new ActivityEvent(
                event.userId(),
                "CATEGORY_UPDATED",
                "CATEGORY",
                event.categoryId(),
                Map.of(),
                Map.of(),
                event.occurredAt()
        );
        repository.save(doc);
    }

    //   - onCategoryDeleted(CategoryDeletedEvent)
    @EventListener
    public void onCategoryDeleted(CategoryDeletedEvent event) {
        log.debug("Recording CategoryDeleted event for categoryId={}", event.categoryId());
        Map<String, Object> before = new HashMap<>();
        before.put("name", event.name());

        ActivityEvent doc = new ActivityEvent(
                event.userId(),
                "CATEGORY_DELETED",
                "CATEGORY",
                event.categoryId(),
                before,
                Map.of(),
                event.occurredAt()
        );
        repository.save(doc);
    }
}
