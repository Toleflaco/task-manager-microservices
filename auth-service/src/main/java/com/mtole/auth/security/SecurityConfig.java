package com.mtole.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TODO S3: This SecurityConfig is a placeholder for Bloque 2 compilation.
 * The full security setup will be redesigned in Sesión 3:
 * - JWT validation moves entirely to api-gateway (perimeter security).
 * - Rate limiting moves entirely to api-gateway.
 * - auth-service keeps only BCrypt for login credential validation
 *   and public endpoints (/auth/login, /auth/refresh, /users registration).
 *
 * TODO S3: Este SecurityConfig es placeholder para que Bloque 2 compile.
 * La configuración completa de seguridad se rediseña en Sesión 3:
 * - Validación JWT se mueve entera al api-gateway (perimeter security).
 * - Rate limiting se mueve entero al api-gateway.
 * - auth-service se queda solo con BCrypt para validación de
 *   credenciales en login y endpoints públicos (/auth/login,
 *   /auth/refresh, /users registro).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Minimal chain: stateless, CSRF off, permitAll for now.
        // Full rules (public vs authenticated endpoints) come in Sesión 3
        // when the Gateway propagates X-User-Id and auth-service knows
        // which endpoints require an authenticated request.
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
