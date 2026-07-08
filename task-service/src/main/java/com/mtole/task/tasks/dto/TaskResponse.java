package com.mtole.task.tasks.dto;

import com.mtole.task.tasks.Priority;
import com.mtole.task.tasks.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

public record TaskResponse(
        @Schema(description = "Task id", example = "32")
        Long id,
        @Schema(description = "Task title", example = "Work in roadmap Java")
        String title,
        @Schema(description = "Task description", example = "Working on the Java roadmap to become a professional backend developer with Claude")
        String description,
        @Schema(description = "Task status", example = "PENDING")
        TaskStatus status,
        @Schema(description = "Task priority", example = "LOW")
        Priority priority,
        @Schema(description = "Date created task", example = "2026-06-02T16:02:30+02:00")
        OffsetDateTime createdAt,
        @Schema(description = "Task update date", example = "2026-01-15T10:30:00+02:00")
        OffsetDateTime updatedAt,
        @Schema(description = "row version", example = "0")
        Long version,
        @Schema(description = "Task dueDate", example = "2026-06-02T16:02:30+02:00")
        OffsetDateTime dueDate,
        @Schema(description = "Completion timestamp, null if task is not completed yet", example = "2026-06-02T16:02:30+02:00")
        OffsetDateTime completedAt,
        @Schema(description = "Category Id", example = "12")
        Long categoryId,
        @Schema(description = "Category name (denormalized for read convenience)", example = "Trabajo")
        String categoryName
) {
}
