package com.mtole.task.tasks.dto;

import com.mtole.task.tasks.Priority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record TaskCreateRequest(
        @Schema(description = "Task title", example = "Work in roadmap Java")
        @NotBlank @Size(min=2, max=100) String title,
        @Schema(description ="Task description", example = "Working on the Java roadmap to become a professional backend developer with Claude")
        String description,
        @Schema(description = "Task priority", example = "LOW")
        @NotNull Priority priority,
        @Schema(description = "Task dueDate", example = "2026-06-02T16:02:30.123+02:00")
        OffsetDateTime dueDate,
        @Schema(description = "Category Id", example = "12")
        Long categoryId
        ) {
}
