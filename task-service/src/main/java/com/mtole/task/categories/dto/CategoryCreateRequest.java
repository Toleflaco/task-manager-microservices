package com.mtole.task.categories.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryCreateRequest(
        @Schema(description = "Category name", example="Work")
        @NotBlank @Size(min=2, max=50) String name,
        @Schema(description="Category description", example="Work-related category")
        String description
) {
}
