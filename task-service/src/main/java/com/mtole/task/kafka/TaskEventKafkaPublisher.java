package com.mtole.task.kafka;

import com.mtole.task.kafka.events.TaskEvent;
import com.mtole.task.kafka.events.TaskEventType;
import com.mtole.task.tasks.events.TaskCreatedEvent;
import com.mtole.task.tasks.events.TaskDeletedEvent;
import com.mtole.task.tasks.events.TaskStatusChangedEvent;
import com.mtole.task.tasks.events.TaskUpdatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

import static com.mtole.task.kafka.KafkaConfig.TOPIC_TASK_EVENTS;

/**
 * Publica eventos de dominio de Task al topic Kafka `task.events`
 * tras el commit exitoso de la transacción JPA.
 *
 * Escucha los ApplicationEvent locales publicados por TaskService y
 * los traduce a TaskEvent (contrato Kafka thin) con eventId nuevo.
 * El eventId es generado aquí porque pertenece al contrato de Kafka,
 * no al evento local Spring.
 *
 * Consistencia: @TransactionalEventListener(AFTER_COMMIT) garantiza que
 * eventos de transacciones fallidas no se publican. No garantiza publicación
 * ante fallo del broker (deuda técnica: transactional outbox, ADR-007).
 */
@Component
public class TaskEventKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(TaskEventKafkaPublisher.class);

    private final KafkaTemplate<String, TaskEvent> kafkaTemplate;

    public TaskEventKafkaPublisher(KafkaTemplate<String, TaskEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        publish(event.userId(), event.taskId(), TaskEventType.CREATED, event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskUpdated(TaskUpdatedEvent event) {
        publish(event.userId(), event.taskId(), TaskEventType.UPDATED, event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskStatusChanged(TaskStatusChangedEvent event) {
        publish(event.userId(), event.taskId(), TaskEventType.STATUS_CHANGED, event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskDeleted(TaskDeletedEvent event) {
        publish(event.userId(), event.taskId(), TaskEventType.DELETED, event.occurredAt());
    }

    private void publish(Long userId, Long taskId, TaskEventType type, java.time.Instant occurredAt) {
        TaskEvent event = new TaskEvent(
                UUID.randomUUID(),
                type,
                userId,
                taskId,
                occurredAt
        );
        String key = userId.toString();
        kafkaTemplate.send(TOPIC_TASK_EVENTS, key, event);
        log.debug("Published TaskEvent {} for user={} task={} type={}",
                event.eventId(), userId, taskId, type);
    }
}
