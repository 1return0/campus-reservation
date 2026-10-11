package com.campusreserve.gateway.web;

import com.campusreserve.common.web.RequestCorrelation;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Establishes one request ID at ingress and propagates it to every routed service. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayRequestCorrelationFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = RequestCorrelation.normalize(
                exchange.getRequest().getHeaders().getFirst(RequestCorrelation.HEADER_NAME)
        );
        exchange.getResponse().getHeaders().set(RequestCorrelation.HEADER_NAME, requestId);
        ServerWebExchange correlated = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(RequestCorrelation.HEADER_NAME, requestId)))
                .build();
        return chain.filter(correlated);
    }
}
