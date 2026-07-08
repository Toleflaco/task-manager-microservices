package com.mtole.task.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Separates JPA and Mongo repository scanning into disjoint packages so
 * Spring Data doesn't try to bootstrap the same repository twice or
 * mix drivers. In task-service the JPA side owns tasks and categories;
 * the Mongo side owns the activity event log.
 *
 * ---
 *
 * Separa el escaneo de repositorios JPA y Mongo en paquetes disjuntos
 * para que Spring Data no intente arrancar el mismo repositorio dos
 * veces ni mezcle drivers. En task-service el lado JPA es dueño de
 * tasks y categories; el lado Mongo es dueño del log de eventos de
 * actividad.
 */
@Configuration
@EnableJpaRepositories(basePackages = {
        "com.mtole.task.tasks",
        "com.mtole.task.categories"
})
@EnableMongoRepositories(basePackages = "com.mtole.task.activity")
public class RepositoryScanConfig {
}
