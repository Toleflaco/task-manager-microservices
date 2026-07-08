package com.mtole.task.tasks.events;

import java.time.Instant;

/**
 * Evento de dominio: se ha creado una task.
 *
 * Publicado por TaskService.create() tras persistir la entidad.
 * Inmutable (record). Sin lógica.
 */
public record TaskCreatedEvent(
        Long taskId,
        Long userId,
        String title,
        String status,
        Long categoryId,
        Instant occurredAt
) {}

