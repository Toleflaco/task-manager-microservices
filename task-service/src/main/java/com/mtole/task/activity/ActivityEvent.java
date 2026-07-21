package com.mtole.task.activity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Evento de auditoría / activity log.
 *
 * Documento inmutable: una vez escrito en MongoDB, no se modifica.
 * Por eso no lleva @Version ni anotaciones de auditoría — todos los
 * mecanismos infra-managed de las entidades JPA (timestamps de
 * modificación, autor de última modificación, etc.) no aplican aquí.
 *
 * El schema es deliberadamente flexible:
 * - 'before' y 'after' son Map<String, Object> porque cada tipo de
 *   action guarda campos distintos. Aprovechan la naturaleza
 *   document-store de MongoDB.
 * - 'action' y 'resourceType' son String (no enum) para no romper la
 *   deserialización de eventos viejos si en el futuro se renombran
 *   o se eliminan tipos de acción.
 */
@Document(collection = "activity_events")
@CompoundIndex(
        name = "userId_1_timestamp_-1",
        def = "{'userId': 1, 'timestamp': -1}"
)
public class ActivityEvent {

    @Id
    private String id;

    private Long userId;

    private String action;

    private String resourceType;

    private Long resourceId;

    private Map<String, Object> before;

    private Map<String, Object> after;

    private Instant timestamp;

    // Constructor protected para deserialización (igual que en JPA).
    protected ActivityEvent() {}

    // Constructor de uso normal: el id lo asigna MongoDB al insertar.
    public ActivityEvent(
            Long userId,
            String action,
            String resourceType,
            Long resourceId,
            Map<String, Object> before,
            Map<String, Object> after,
            Instant timestamp) {
        this.userId = userId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.before = before;
        this.after = after;
        this.timestamp = timestamp;
    }
    // Constructor con id explícito. Usado por el consumer para poblar
    // el _id de Mongo con el eventId del contrato Kafka (idempotencia).
    public ActivityEvent(
            String id,
            Long userId,
            String action,
            String resourceType,
            Long resourceId,
            Map<String, Object> before,
            Map<String, Object> after,
            Instant timestamp) {
        this.id = id;
        this.userId = userId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.before = before;
        this.after = after;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public Map<String, Object> getBefore() {
        return before;
    }

    public Map<String, Object> getAfter() {
        return after;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
