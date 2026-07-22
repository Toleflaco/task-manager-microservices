package com.mtole.task.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_TASK_EVENTS = "task.events";
    public static final String TOPIC_CATEGORY_EVENTS = "category.events";

    @Bean
    public NewTopic taskEventsTopic() {
        return TopicBuilder.name(TOPIC_TASK_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic categoryEventsTopic() {
        return TopicBuilder.name(TOPIC_CATEGORY_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }


    /**
     * ErrorHandler personalizado para consumers Kafka.
     *
     * Sustituye el DefaultErrorHandler por defecto de Spring Kafka, que aplica
     * FixedBackOff(0ms, 9 intentos) y descarta el mensaje al agotar reintentos.
     * Aquí usamos ExponentialBackOff sin techo total: reintentos indefinidos
     * con espera creciente hasta 30s por intento. Preferimos consumer bloqueado
     * en partición (incidente visible vía lag) a pérdida silenciosa.
     *
     * Aplica a todos los @KafkaListener del contexto por resolución por tipo.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxInterval(30_000L);
        return new DefaultErrorHandler(backOff);
    }
}
