package com.blackroth.training.gateway.filter;

import com.blackroth.training.gateway.util.CorrelationIdUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationIdGlobalFilterTest {

    private CorrelationIdGlobalFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CorrelationIdGlobalFilter();
    }

    @Test
    @DisplayName("Propagates valid incoming X-Correlation-ID to downstream request and response")
    void testPropagatesValidIncomingCorrelationId() {
        String validId = "corr-gateway-12345";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header(CorrelationIdUtils.CORRELATION_ID_HEADER, validId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(CorrelationIdUtils.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertEquals(validId, downstreamHeader.get());
        assertEquals(validId, exchange.getResponse().getHeaders().getFirst(CorrelationIdUtils.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("Generates secure UUID fallback when incoming X-Correlation-ID is missing")
    void testGeneratesUuidWhenHeaderMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(CorrelationIdUtils.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(downstreamHeader.get());
        assertDoesNotThrow(() -> UUID.fromString(downstreamHeader.get()));
        assertEquals(downstreamHeader.get(), exchange.getResponse().getHeaders().getFirst(CorrelationIdUtils.CORRELATION_ID_HEADER));
    }

    @Test
    @DisplayName("Generates safe UUID fallback when incoming X-Correlation-ID is malformed or exceeds max length")
    void testGeneratesFallbackWhenMalformedOrOverlong() {
        String malformedHeader = "bad\r\nHeaderWithCRLF";
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders")
                .header(CorrelationIdUtils.CORRELATION_ID_HEADER, malformedHeader)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamHeader = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeader.set(mutatedExchange.getRequest().getHeaders().getFirst(CorrelationIdUtils.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertNotNull(downstreamHeader.get());
        assertNotEquals(malformedHeader, downstreamHeader.get());
        assertDoesNotThrow(() -> UUID.fromString(downstreamHeader.get()));
        assertEquals(downstreamHeader.get(), exchange.getResponse().getHeaders().getFirst(CorrelationIdUtils.CORRELATION_ID_HEADER));
    }
}
