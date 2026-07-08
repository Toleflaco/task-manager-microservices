package com.mtole.task.activity.dto;


import java.time.Instant;
import java.util.Map;

public record ActivityStatsResponse(
        long totalEvents,
        Instant firstEvent,
        Instant lastEvent,
        Map<String, Long> eventsByAction,
        Map<String, Long> eventsByResourceType
) {}
