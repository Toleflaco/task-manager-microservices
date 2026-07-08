package com.mtole.auth.common;

import jakarta.servlet.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter implements Filter {
    private static final String REQUEST_ID = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        try {
            MDC.put(REQUEST_ID, UUID.randomUUID().toString());
            chain.doFilter(request, response);

        } finally {
            MDC.remove(REQUEST_ID);
        }
    }
}
