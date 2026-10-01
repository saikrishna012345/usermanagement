package com.blackroth.training.mobilebackend.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CorrelationIdFeignInterceptor {

    private static final String CORRELATION_ID = "X-Correlation-ID";

    @Bean
    public RequestInterceptor correlationIdRequestInterceptor() {

        return requestTemplate -> {

            String correlationId =
                    MDC.get("correlationId");

            if (correlationId != null && !correlationId.isBlank()) {

                requestTemplate.header(
                        CORRELATION_ID,
                        correlationId
                );
            }
        };
    }
}