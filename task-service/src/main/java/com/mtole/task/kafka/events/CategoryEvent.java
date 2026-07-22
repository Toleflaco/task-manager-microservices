package com.mtole.task.kafka.events;

import java.time.Instant;
import java.util.UUID;

public record CategoryEvent(
        UUID eventId,
        CategoryEventType type,
        Long userId,
        Long categoryId,
        Instant timestamp
) {}
