package com.mtole.task.tasks.events;

import java.time.Instant;

public record TaskStatusChangedEvent(
        Long taskId,
        Long userId,
        String oldStatus,
        String newStatus,
        Instant occurredAt
) {}
