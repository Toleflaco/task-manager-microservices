package com.mtole.task.activity;

import java.time.Instant;

public record ActivityStatsFilter(
        Instant from,
        Instant to
) {}
