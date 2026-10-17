package com.mtole.task.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mtole.task.kafka.events.TaskEvent;
import com.mtole.task.kafka.events.TaskEventType;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "outbox.poller.initial-delay-ms=100")
@Import(OutboxPollerIntegrationTest.TestConsumerConfig.class)
public class OutboxPollerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine");

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:6.0");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));


    @TestConfiguration
    static class TestConsumerConfig {

        final List<ConsumerRecord<String, TaskEvent>> recibidos = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = "task.events", groupId = "test-outbox-poller")
        void onEvent(ConsumerRecord<String, TaskEvent> record) {
            recibidos.add(record);
        }
    }

    @Autowired
    OutboxRepository outboxRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestConsumerConfig testConsumer;


    @Test
    void publicaFilaPendienteYmarcaPublishedAt() throws JsonProcessingException {

        UUID expectedEventId = UUID.randomUUID();
        Long expectedUserId = 48L;
        Long expectedTaskId = 5L;

        TaskEvent event = new TaskEvent(
                expectedEventId,
                TaskEventType.CREATED,
                expectedUserId,
                expectedTaskId,
                Instant.now()
        );

        String payload = objectMapper.writeValueAsString(event);
        OutboxEvent outboxEvent = new OutboxEvent(
                "task",
                expectedTaskId.toString(),
                "task.created",
                payload
        );
        OutboxEvent saved = outboxRepository.save(outboxEvent);

        // Esperar
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(
                        () -> {
                            assertThat(testConsumer.recibidos).hasSize(1);
                            ConsumerRecord<String, TaskEvent> received = testConsumer.recibidos.get(0);
                            assertThat(received.key()).isEqualTo(expectedUserId.toString());
                            assertThat(received.value().eventId()).isEqualTo(expectedEventId);
                            assertThat(received.value().userId()).isEqualTo(expectedUserId);
                            assertThat(received.value().taskId()).isEqualTo(expectedTaskId);
                            assertThat(received.value().type()).isEqualTo(TaskEventType.CREATED);
                            OutboxEvent recargada = outboxRepository.findById(saved.getId()).orElseThrow(()->new NoSuchElementException("No existe el elemento"));
                            assertThat(recargada.getPublishedAt()).isNotNull();
                        });


    }
}
