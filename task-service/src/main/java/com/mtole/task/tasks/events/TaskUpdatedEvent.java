package com.mtole.task.tasks.events;

import java.time.Instant;

public record TaskUpdatedEvent(
        Long taskId,
        Long userId,
        Instant occurredAt
) {}
