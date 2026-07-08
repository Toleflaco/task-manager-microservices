package com.mtole.auth.login;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @Schema(description = "Opaque refresh token used to request new access tokens", example = "550e8400-e29b-41d4-a716-446655440000")
        @NotBlank
        String refreshToken
) {
}
