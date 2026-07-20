package com.mtole.task.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

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
}
