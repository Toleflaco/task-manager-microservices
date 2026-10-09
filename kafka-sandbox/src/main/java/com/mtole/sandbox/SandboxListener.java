package com.mtole.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class SandboxListener {

    private static final Logger log = LoggerFactory.getLogger(SandboxListener.class);

    @KafkaListener(topics = "sandbox.offset-management")
    public void onMessage(PingEvent evento,
                          @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                          @Header(KafkaHeaders.OFFSET) long offset,
                          Acknowledgment ack) {
        log.info("Recibido de partición {} offset {}: source={} when={} payload={}",
                partition, offset, evento.source(),evento.when(),evento.payload());
        if ("msg-3".equals(evento.payload())) {
            throw new RuntimeException("Fallo intencionado en msg-3");
        }
        ack.acknowledge();
    }
}
