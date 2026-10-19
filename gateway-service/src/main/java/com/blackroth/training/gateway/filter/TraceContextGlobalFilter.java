package com.blackroth.training.gateway.filter;

import com.blackroth.training.gateway.util.TraceContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter for W3C distributed trace context propagation at API Gateway.
 * Accepts incoming 'traceparent' header when valid, or generates a compliant W3C
 * trace context when missing, blank, or malformed. Mutates downstream request
 * and client response headers.
 */
@Component
public class TraceContextGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TraceContextGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String rawTraceparent = exchange.getRequest()
                .getHeaders()
                .getFirst(TraceContextUtils.TRACEPARENT_HEADER);

        String traceparent = TraceContextUtils.resolveTraceparent(rawTraceparent);
        String traceId = TraceContextUtils.extractTraceId(traceparent);

        ServerHttpRequest mutatedRequest = exchange.getRequest()
                .mutate()
                .header(TraceContextUtils.TRACEPARENT_HEADER, traceparent)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        exchange.getResponse()
                .getHeaders()
                .set(TraceContextUtils.TRACEPARENT_HEADER, traceparent);

        log.info("Gateway trace: traceId={}, traceparent={}, method={}, path={}",
                traceId, traceparent, mutatedRequest.getMethod(), mutatedRequest.getURI().getPath());

        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        // Runs immediately after CorrelationIdGlobalFilter (Ordered.HIGHEST_PRECEDENCE)
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
