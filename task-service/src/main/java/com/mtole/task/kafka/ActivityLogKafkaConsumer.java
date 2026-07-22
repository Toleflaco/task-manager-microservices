package com.mtole.task.kafka;

import org.springframework.dao.DuplicateKeyException;
import com.mtole.task.activity.ActivityEvent;
import com.mtole.task.kafka.events.CategoryEvent;
import com.mtole.task.kafka.events.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.mtole.task.kafka.KafkaConfig.TOPIC_CATEGORY_EVENTS;
import static com.mtole.task.kafka.KafkaConfig.TOPIC_TASK_EVENTS;

/**
 * Consumer del audit log. Escucha los topics `task.events` y
 * `category.events`, y persiste un documento ActivityEvent en Mongo
 * por cada mensaje recibido.
 *
 * Idempotencia: el _id del documento en Mongo es el eventId del
 * contrato Kafka. Si el mismo mensaje se procesa más de una vez
 * (por reintentos, reset de offsets, etc.), el insert lanza
 * DuplicateKeyException y se ignora silenciosamente. Efecto:
 * exactamente un documento por eventId. Patrón "effectively
 * exactly-once".
 *
 * Semántica: at-least-once. El commit del offset se hace manualmente
 * tras confirmación de escritura en Mongo. Si la escritura falla,
 * el offset no se commitea y Kafka reintenta más tarde.
 *
 * Detalle de negocio: los eventos Kafka son thin (solo IDs y
 * metadatos). El activity log resultante no guarda title/status/name.
 * Trade-off documentado en ADR-007.
 */
@Component
public class ActivityLogKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ActivityLogKafkaConsumer.class);

    private final MongoTemplate mongoTemplate;

    public ActivityLogKafkaConsumer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @KafkaListener(topics = TOPIC_TASK_EVENTS, groupId = "activity-log-writer")
    public void onTaskEvent(TaskEvent event, Acknowledgment ack) {
        log.debug("Received TaskEvent {} type={} task={}", event.eventId(), event.type(), event.taskId());

        ActivityEvent doc = new ActivityEvent(
                event.eventId().toString(),
                event.userId(),
                "TASK_" + event.type().name(),
                "TASK",
                event.taskId(),
                Map.of(),
                Map.of(),
                event.timestamp()
        );

        persist(doc, event.eventId().toString());
        ack.acknowledge();
    }

    @KafkaListener(topics = TOPIC_CATEGORY_EVENTS, groupId = "activity-log-writer")
    public void onCategoryEvent(CategoryEvent event, Acknowledgment ack) {
        log.debug("Received CategoryEvent {} type={} category={}",
                event.eventId(), event.type(), event.categoryId());

        ActivityEvent doc = new ActivityEvent(
                event.eventId().toString(),
                event.userId(),
                "CATEGORY_" + event.type().name(),
                "CATEGORY",
                event.categoryId(),
                Map.of(),
                Map.of(),
                event.timestamp()
        );

        persist(doc, event.eventId().toString());
        ack.acknowledge();
    }

    private void persist(ActivityEvent doc, String eventId) {
        try {
            mongoTemplate.insert(doc);
            log.debug("Persisted ActivityEvent id={}", eventId);
        } catch (DuplicateKeyException ex) {
            log.info("Duplicate ActivityEvent skipped id={}", eventId);
        }
    }
}
