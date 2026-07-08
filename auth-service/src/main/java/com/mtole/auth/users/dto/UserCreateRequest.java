package com.mtole.auth.users.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserCreateRequest(
        @Schema(description = "User name", example="Juan Mendoza")
        @NotBlank @Size(min=2, max=50) String name,
        @Schema(description="User email", example="juan.mendoza@mendoza.es")
        @NotBlank @Email String email,
        @Schema(description= "User password", example="hola2332")
        @NotBlank(message = "Password is required")
        @Size(min=8,max=72,message = "Password must be between 8 and 72 characters")
        String password
) {
}
