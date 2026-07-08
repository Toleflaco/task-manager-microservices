package com.mtole.auth.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

public record UserResponse(
        @Schema(description = "User id", example = "32")
        Long id,
        @Schema(description = "User name", example = "Juan Mendoza")
        String name,
        @Schema(description = "User email", example = "juan.mendoza@mendoza.es")
        String email,
        @Schema(description = "User creation date", example = "2026-01-15T10:30:00+02:00")
        OffsetDateTime createdAt,
        @Schema(description = "User update date", example = "2026-01-15T10:30:00+02:00")
        OffsetDateTime updatedAt,
        @Schema(description = "row version", example = "0")
        Long version

) {
}
