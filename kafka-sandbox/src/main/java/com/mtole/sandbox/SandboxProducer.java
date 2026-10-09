package com.mtole.sandbox;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class SandboxProducer {

    private static final String TOPIC = "sandbox.eventos";

    private final KafkaTemplate<String,PingEvent> kafkaTemplate;

    public SandboxProducer(KafkaTemplate<String, PingEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping("/publish")
    public String publish (@RequestBody String payload) {
        PingEvent evento = new PingEvent("kafka-sandbox", Instant.now(),payload);
        kafkaTemplate.send(TOPIC,evento);
        return "Publicado: " + evento + "\n";
    }
}
