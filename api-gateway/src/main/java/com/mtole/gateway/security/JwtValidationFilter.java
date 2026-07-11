package com.mtole.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.List;

@Component  // Spring lo detecta y aplica como GlobalFilter automáticamente
public class JwtValidationFilter implements GlobalFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtValidationFilter.class);
    private static final List<String> PUBLIC_PATHS = List.of("/actuator", "/auth/login", "/auth/refresh", "/users");

    private final SecretKey signingKey;   // construida en el constructor a partir de JwtProperties

    public JwtValidationFilter(JwtProperties props) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.secret()));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // obtener path de la request
        String path = exchange.getRequest().getPath().value();


        // si path empieza por alguno de los públicos (/actuator, /auth/login,/auth/refresh, /users): dejo pasar sin tocar
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // obtener header "Authorization",  si no existe o no empieza por "Bearer ":  401
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // 401
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        // extraer token (quitar los 7 caracteres "Bearer " del principio)
        String token = authHeader.substring(7);

        //    try:
        //        claims = jwtParser.parseClaims(token)   // valida firma + expiración
        //    catch (cualquier JwtException):
        //        log.warn("JWT inválido en path {}", path)
        //        return respuesta 401
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            log.warn("JWT inválido en path {}: {}", path, e.getClass().getSimpleName());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String userId = claims.getSubject();
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.header("X-User-Id", userId))
                .build();
        return chain.filter(mutated);
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }
}
