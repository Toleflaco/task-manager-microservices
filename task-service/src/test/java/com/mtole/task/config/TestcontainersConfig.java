package com.mtole.task.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers configuration for local development.
 * Registers reusable Postgres and MongoDB containers that Spring
 * Boot connects to automatically via @ServiceConnection.
 *
 * Not for production. See debt for Phase 11: move to Maven profile.
 *
 * ---
 *
 * Configuración de Testcontainers para desarrollo local.
 * Registra contenedores Postgres y MongoDB reusables a los que
 * Spring Boot se conecta automáticamente vía @ServiceConnection.
 *
 * No para producción. Ver debt para Fase 11: mover a perfil Maven.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:14"))
                .withDatabaseName("microservices_db")
                .withReuse(true);
    }

    @Bean
    @ServiceConnection
    public MongoDBContainer mongoContainer() {
        return new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
                .withReuse(true);
    }
}
