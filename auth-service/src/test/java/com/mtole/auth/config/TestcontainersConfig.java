package com.mtole.auth.config;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers configuration for local development.
 * Registers a reusable Postgres container that Spring Boot connects
 * to automatically via @ServiceConnection.
 *
 * Not for production. See debt for Phase 11: move to Maven profile.
 *
 * ---
 *
 * Configuración de Testcontainers para desarrollo local.
 * Registra un contenedor Postgres reusable al que Spring Boot se
 * conecta automáticamente vía @ServiceConnection.
 *
 * No para producción. Ver debt para Fase 11: mover a perfil Maven.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:14"))
                .withReuse(true)
                .withDatabaseName("microservices_db");
    }
}
