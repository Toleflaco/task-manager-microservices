package com.mtole.task.tasks;

public record TaskFilter(
        TaskStatus status,
        Priority priority,
        String categoryName
) {
}
