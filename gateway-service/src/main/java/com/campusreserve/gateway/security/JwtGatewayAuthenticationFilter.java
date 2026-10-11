package com.campusreserve.gateway.security;

import com.campusreserve.security.CampusJwtService;
import com.campusreserve.security.JwtPrincipal;
import com.campusreserve.security.JwtVerificationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** Validates browser tokens before routing and removes forged identity headers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class JwtGatewayAuthenticationFilter implements GlobalFilter {

    private static final String USER_HEADER = "X-Authenticated-User";
    private static final String ROLES_HEADER = "X-Authenticated-Roles";

    private final CampusJwtService jwtService;

    public JwtGatewayAuthenticationFilter(CampusJwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/") || path.equals("/index.html")
                || path.equals("/app.js") || path.equals("/styles.css")
                || path.startsWith("/api/v1/auth/") || path.startsWith("/actuator/")) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }
        try {
            JwtPrincipal principal = jwtService.verify(authorization.substring("Bearer ".length()));
            ServerWebExchange authenticated = exchange.mutate()
                    .request(request -> request.headers(headers -> {
                        headers.remove(USER_HEADER);
                        headers.remove(ROLES_HEADER);
                        headers.set(USER_HEADER, principal.subject());
                        headers.set(ROLES_HEADER, String.join(",", principal.roles()));
                    }))
                    .build();
            return chain.filter(authenticated);
        } catch (JwtVerificationException exception) {
            return unauthorized(exchange);
        }
    }

    private static Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer body = exchange.getResponse().bufferFactory()
                .wrap("{\"code\":401,\"message\":\"unauthorized\",\"data\":null}"
                        .getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(body));
    }
}
