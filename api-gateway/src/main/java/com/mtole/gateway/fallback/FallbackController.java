package com.mtole.gateway.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Fallback endpoints invoked internally by the {@code CircuitBreaker}
 * filter when a downstream service is unavailable or the breaker is open.
 *
 * <p>Each fallback returns an RFC 7807 {@code application/problem+json}
 * response with HTTP 503, consistent with the error handling convention
 * used across the platform.
 *
 * <p>These endpoints are not intended for direct client consumption:
 * they are reached via {@code forward:} directives from the gateway
 * routes' {@code CircuitBreaker} filter.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping("/tasks")
    public Mono<ResponseEntity<ProblemDetail>> taskServiceFallback() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The task service is temporarily unavailable. Please retry in a few seconds."
        );
        problem.setTitle("Service Unavailable");
        problem.setInstance(java.net.URI.create("/fallback/tasks"));

        return Mono.just(
                ResponseEntity
                        .status(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(problem)
        );
    }
}
