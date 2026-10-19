package com.blackroth.training.gateway.filter;

import com.blackroth.training.gateway.util.TraceContextUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TraceContextGlobalFilterTest {

    private TraceContextGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TraceContextGlobalFilter();
    }

    @Test
    @DisplayName("Propagates valid incoming traceparent to downstream request and response")
    void testPropagatesValidIncomingTraceparent() {
        String validTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header(TraceContextUtils.TRACEPARENT_HEADER, validTraceparent)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(TraceContextUtils.TRACEPARENT_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertEquals(validTraceparent, downstreamHeader.get());
        assertEquals(validTraceparent, exchange.getResponse().getHeaders().getFirst(TraceContextUtils.TRACEPARENT_HEADER));
    }

    @Test
    @DisplayName("Generates valid W3C traceparent fallback when incoming header is missing")
    void testGeneratesFallbackWhenMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(TraceContextUtils.TRACEPARENT_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(downstreamHeader.get());
        assertTrue(TraceContextUtils.isValidTraceparent(downstreamHeader.get()));
        assertEquals(downstreamHeader.get(), exchange.getResponse().getHeaders().getFirst(TraceContextUtils.TRACEPARENT_HEADER));
    }

    @Test
    @DisplayName("Generates valid W3C traceparent fallback when incoming header is malformed or all zeros")
    void testGeneratesFallbackWhenMalformed() {
        String malformed = "00-00000000000000000000000000000000-0000000000000000-01";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header(TraceContextUtils.TRACEPARENT_HEADER, malformed)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(TraceContextUtils.TRACEPARENT_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(downstreamHeader.get());
        assertTrue(TraceContextUtils.isValidTraceparent(downstreamHeader.get()));
        assertNotEquals(malformed, downstreamHeader.get());
        assertEquals(downstreamHeader.get(), exchange.getResponse().getHeaders().getFirst(TraceContextUtils.TRACEPARENT_HEADER));
    }
}
