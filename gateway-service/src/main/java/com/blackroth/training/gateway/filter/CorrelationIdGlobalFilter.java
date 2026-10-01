package com.blackroth.training.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class CorrelationIdGlobalFilter
        implements GlobalFilter, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    private static final String CORRELATION_ID =
            "X-Correlation-ID";

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {

        String correlationId =
                exchange.getRequest()
                        .getHeaders()
                        .getFirst(CORRELATION_ID);

        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String finalCorrelationId = correlationId;

        ServerHttpRequest request =
                exchange.getRequest()
                        .mutate()
                        .header(
                                CORRELATION_ID,
                                finalCorrelationId
                        )
                        .build();

        ServerWebExchange mutatedExchange =
                exchange.mutate()
                        .request(request)
                        .build();

        exchange.getResponse()
                .getHeaders()
                .set(
                        CORRELATION_ID,
                        finalCorrelationId
                );

        log.info(
                "Gateway request: correlationId={}, method={}, path={}",
                finalCorrelationId,
                request.getMethod(),
                request.getURI().getPath()
        );

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}