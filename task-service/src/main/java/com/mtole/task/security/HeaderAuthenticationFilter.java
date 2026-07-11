package com.mtole.task.security;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component  // Spring lo detecta como bean; SecurityConfig lo registrará en el chain
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HeaderAuthenticationFilter.class);
    private static final String USER_ID_HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 1. leer header
        String userIdHeader = request.getHeader(USER_ID_HEADER);
        // 2. si null → chain.doFilter + return
        if (userIdHeader == null) {
            chain.doFilter(request, response);
            return;
        }
        // try: parsear a Long, crear auth con 3 args, setAuthentication
        //    catch NumberFormatException: log warn, no autenticar

        try {
            Long userId = Long.parseLong(userIdHeader);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of()
            );
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (NumberFormatException e) {
            log.warn("Header {} inválido (no numérico): {}", USER_ID_HEADER, userIdHeader);
            // No autenticamos, dejamos que el chain responda 401
        }
        // 4. chain.doFilter
        chain.doFilter(request, response);

    }
}
