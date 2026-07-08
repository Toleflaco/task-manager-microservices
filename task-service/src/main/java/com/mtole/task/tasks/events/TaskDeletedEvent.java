package com.mtole.task.tasks.events;

import java.time.Instant;

public record TaskDeletedEvent(
        Long taskId,
        Long userId,
        String title,
        String status,
        Instant occurredAt
) {}
