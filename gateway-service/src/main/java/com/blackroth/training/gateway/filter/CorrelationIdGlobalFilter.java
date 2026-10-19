package com.blackroth.training.gateway.filter;

import com.blackroth.training.gateway.util.CorrelationIdUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class CorrelationIdGlobalFilter
        implements GlobalFilter, Ordered {

    private static final Logger log =
            LoggerFactory.getLogger(CorrelationIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain
    ) {

        String rawCorrelationId =
                exchange.getRequest()
                        .getHeaders()
                        .getFirst(CorrelationIdUtils.CORRELATION_ID_HEADER);

        String correlationId = CorrelationIdUtils.resolveCorrelationId(rawCorrelationId);
        final String finalCorrelationId = correlationId;

        ServerHttpRequest request =
                exchange.getRequest()
                        .mutate()
                        .header(
                                CorrelationIdUtils.CORRELATION_ID_HEADER,
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
                        CorrelationIdUtils.CORRELATION_ID_HEADER,
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