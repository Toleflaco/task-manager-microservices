package com.mtole.task.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtole.task.kafka.events.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.mtole.task.kafka.KafkaConfig.TOPIC_TASK_EVENTS;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, TaskEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long sendTimeoutMs;

    public OutboxPoller(OutboxRepository outboxRepository,
                        KafkaTemplate<String, TaskEvent> kafkaTemplate,
                        ObjectMapper objectMapper,
                        @Value("${outbox.poller.batch-size:100}") int batchSize,
                        @Value("${outbox.poller.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(
            fixedDelayString = "${outbox.poller.fixed-delay-ms:500}",
            initialDelayString = "${outbox.poller.initial-delay-ms:5000}"
    )
    @Transactional
    public void drainOutbox() {
        List<OutboxEvent> pending = outboxRepository.findPendingWithLock(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        int published = 0;
        for (OutboxEvent row : pending) {
            try {
                TaskEvent event = objectMapper.readValue(row.getPayload(), TaskEvent.class);
                kafkaTemplate.send(TOPIC_TASK_EVENTS, event.userId().toString(), event)
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                row.markPublished();
                published++;
                log.debug("Outbox row {} published: eventId={} user={}",
                        row.getId(), event.eventId(), event.userId());
            } catch (JsonProcessingException e) {
                row.recordFailure(describe(e));
                log.error("Outbox row {} has unreadable payload (attempts={})",
                        row.getId(), row.getAttempts(), e);
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                row.recordFailure(describe(e));
                log.info("Outbox poller interrupted, stopping cycle");
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                row.recordFailure(describe(e));
                log.warn("Outbox row {} failed to publish (attempts={})",
                        row.getId(), row.getAttempts(), e);
                break;
            }
        }
        log.info("Outbox cycle: published {} of {}", published, pending.size());
    }

    private static final int MAX_ERROR_LENGTH = 1000;

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getClass().getSimpleName() + ": " + root.getMessage();
        return msg.length() > MAX_ERROR_LENGTH ? msg.substring(0, MAX_ERROR_LENGTH) : msg;
    }
}
