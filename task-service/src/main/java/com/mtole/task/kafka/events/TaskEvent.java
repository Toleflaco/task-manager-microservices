package com.mtole.task.kafka.events;

import java.time.Instant;
import java.util.UUID;

public record TaskEvent(
        UUID eventId,
        TaskEventType type,
        Long userId,
        Long taskId,
        Instant timestamp
){}
