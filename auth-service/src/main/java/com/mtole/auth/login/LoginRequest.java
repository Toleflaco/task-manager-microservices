package com.mtole.auth.login;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Schema(description = "User email", example = "tole@x.com")
        @NotBlank @Email String email,

        @Schema(description = "User password", example = "hola1231")
        @NotBlank String password
        ) {
}
