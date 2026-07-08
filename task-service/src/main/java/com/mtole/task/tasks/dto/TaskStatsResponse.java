package com.mtole.task.tasks.dto;

public record TaskStatsResponse(
        long totalTasks,
        long pendingTasks,
        long inProgressTasks,
        long completedTasks,
        long cancelledTasks
) {
}
