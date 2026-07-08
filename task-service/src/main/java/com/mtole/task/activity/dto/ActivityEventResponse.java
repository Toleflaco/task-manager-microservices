package com.mtole.task.activity.dto;

import java.time.Instant;
import java.util.Map;

public record ActivityEventResponse(
        String id,
        Long userId,
        String action,
        String resourceType,
        Long resourceId,
        Map<String, Object> before,
        Map<String, Object> after,
        Instant timestamp
) {
}
