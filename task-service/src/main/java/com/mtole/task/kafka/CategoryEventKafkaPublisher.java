package com.mtole.task.kafka;

import com.mtole.task.categories.events.CategoryCreatedEvent;
import com.mtole.task.categories.events.CategoryDeletedEvent;
import com.mtole.task.categories.events.CategoryUpdatedEvent;
import com.mtole.task.kafka.events.CategoryEvent;
import com.mtole.task.kafka.events.CategoryEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

import static com.mtole.task.kafka.KafkaConfig.TOPIC_CATEGORY_EVENTS;

/**
 * Publica eventos de dominio de Category al topic Kafka `category.events`
 * tras el commit exitoso de la transacción JPA.
 *
 * Escucha los ApplicationEvent locales publicados por CategoryService y
 * los traduce a CategoryEvent (contrato Kafka thin) con eventId nuevo.
 * El eventId es generado aquí porque pertenece al contrato de Kafka,
 * no al evento local Spring.
 *
 * Consistencia: @TransactionalEventListener(AFTER_COMMIT) garantiza que
 * eventos de transacciones fallidas no se publican. No garantiza publicación
 * ante fallo del broker (deuda técnica: transactional outbox, ADR-007).
 */
@Component
public class CategoryEventKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(CategoryEventKafkaPublisher.class);

    private final KafkaTemplate<String, CategoryEvent> kafkaTemplate;

    public CategoryEventKafkaPublisher(KafkaTemplate<String, CategoryEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCategoryCreated(CategoryCreatedEvent event) {
        publish(event.userId(), event.categoryId(), CategoryEventType.CREATED, event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCategoryUpdated(CategoryUpdatedEvent event) {
        publish(event.userId(), event.categoryId(), CategoryEventType.UPDATED, event.occurredAt());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCategoryDeleted(CategoryDeletedEvent event) {
        publish(event.userId(), event.categoryId(), CategoryEventType.DELETED, event.occurredAt());
    }

    private void publish(Long userId, Long categoryId, CategoryEventType type, java.time.Instant occurredAt) {
        CategoryEvent event = new CategoryEvent(
                UUID.randomUUID(),
                type,
                userId,
                categoryId,
                occurredAt
        );
        String key = userId.toString();
        kafkaTemplate.send(TOPIC_CATEGORY_EVENTS, key, event);
        log.debug("Published CategoryEvent {} for user={} category={} type={}",
                event.eventId(), userId, categoryId, type);
    }
}
