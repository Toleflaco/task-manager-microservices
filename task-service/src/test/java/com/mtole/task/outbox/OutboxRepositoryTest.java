package com.mtole.task.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class OutboxRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14-alpine");

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void savesAndFindsPendingWithLock() {
        OutboxEvent event = new OutboxEvent(
            "task",
            "123",
            "task.created",
            "{\"taskId\":123,\"title\":\"demo\"}"
        );

        outboxRepository.save(event);

        List<OutboxEvent> pending = outboxRepository.findPendingWithLock(10);

        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getAggregateType()).isEqualTo("task");
        assertThat(pending.get(0).getPublishedAt()).isNull();
        assertThat(pending.get(0).getId()).isNotNull();
        assertThat(pending.get(0).getCreatedAt()).isNotNull();
    }
}
