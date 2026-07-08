package com.mtole.auth.login;

import com.mtole.auth.security.JwtProperties;
import com.mtole.auth.security.JwtService;
import com.mtole.auth.users.User;
import com.mtole.auth.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String TOKEN_TYPE = "Bearer";
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtProperties jwtProperties;

    public AuthService(AuthenticationManager authenticationManager, JwtService jwtService, RefreshTokenRepository refreshTokenRepository, UserRepository userRepository, JwtProperties jwtProperties) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {

        log.info("Login attempt for email={}", request.email());
        // 1. Delegar validación de credenciales en AuthenticationManager.
        //    Si las credenciales son malas, lanza BadCredentialsException
        //    (que el GlobalExceptionHandler convertirá en 401).
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        // 2. Si llegamos aquí, las credenciales son válidas.
        //    Recupero el User del repo para obtener el userId (necesario para el JWT).

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalStateException(
                        "User authenticated but not found in repository — inconsistent state"));


        UUID familyId = UUID.randomUUID();  // ← nuevo: UUID en lugar de nextFamilyId()
        TokenPair pair = issueTokenPair(user, familyId);
        log.info("Login successful for userId={}", user.getId());
        return new LoginResponse(pair.accessToken(), pair.refreshToken(),
                pair.expiresInSeconds(), TOKEN_TYPE);
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public RefreshTokenResponse refresh(RefreshTokenRequest request) {
        // 1. Buscar el token presentado. Si no existe → 401.
        RefreshToken oldToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new InvalidRefreshTokenException("Invalid refresh token"));
        // 2. Si está revocado → DETECCIÓN DE REUSO. Revocar familia entera + 401.
        if (oldToken.isRevoked()) {
            log.warn("Refresh token reuse detected for userId={} familyId={}",
                    oldToken.getUser().getId(), oldToken.getFamilyId());
            refreshTokenRepository.revokeFamily(oldToken.getFamilyId());
            throw new InvalidRefreshTokenException("Invalid refresh token");
        }

        // 3. Si ha expirado → 401.
        if (oldToken.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw new InvalidRefreshTokenException("Invalid refresh token");
        }

        // 4. Marcar el token presentado como revoked = true.
        oldToken.setRevoked(true);

        // 5. Emitir par nuevo con familyId HEREDADO del viejo.
        TokenPair pair = issueTokenPair(oldToken.getUser(), oldToken.getFamilyId());

        // 6. Envolver en RefreshTokenResponse y devolver.
        log.info("Refresh successful for userId={}", oldToken.getUser().getId());
        return new RefreshTokenResponse(pair.accessToken(), pair.refreshToken(),
                pair.expiresInSeconds(), TOKEN_TYPE);
    }

    private TokenPair issueTokenPair(User user, UUID familyId) {
        String refreshTokenString = UUID.randomUUID().toString();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC)
                .plus(jwtProperties.refreshExpiration());

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken(refreshTokenString);
        refreshToken.setUser(user);
        refreshToken.setFamilyId(familyId);
        refreshToken.setExpiresAt(expiresAt);
        refreshTokenRepository.save(refreshToken);

        String accessToken = jwtService.generateAccessToken(user.getId());
        long expiresInSeconds = jwtProperties.accessExpiration().toSeconds();
        return new TokenPair(accessToken, refreshTokenString, expiresInSeconds);
    }

    private record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
    }
}
