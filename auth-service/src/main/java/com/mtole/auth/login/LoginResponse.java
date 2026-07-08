package com.mtole.auth.login;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
        @Schema(description = "JWT access token", example = "eyJhbGciOiJIUzI1NiJ9...")
        String accessToken,
        @Schema(description = "Opaque refresh token used to request new access tokens", example = "550e8400-e29b-41d4-a716-446655440000")
        String refreshToken,
        @Schema(description = "Seconds until access token expires", example = "60")
        long expiresIn,
        @Schema(description = "Token type, always 'Bearer' (RFC 6750)", example = "Bearer")
        String tokenType
) { }