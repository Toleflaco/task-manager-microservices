package com.mtole.sandbox;

import java.time.Instant;

public record PingEvent(String source, Instant when, String payload) {
}
