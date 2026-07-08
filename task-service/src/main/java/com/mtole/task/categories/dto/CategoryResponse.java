package com.mtole.task.categories.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

public record CategoryResponse(
        @Schema(description = "Category id", example = "32")
        Long id,
        @Schema(description = "Category name", example = "Work")
        String name,
        @Schema(description = "Category description", example = "Work-related category")
        String description,
        @Schema(description = "Category creation date", example = "2026-01-15T10:30:00")
        OffsetDateTime createdAt,
        @Schema(description = "Category update date", example = "2026-01-15T10:30:00+02:00")
        OffsetDateTime updatedAt,
        @Schema(description = "row version", example = "0")
        Long version

) {
}
