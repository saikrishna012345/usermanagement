package com.blackroth.training.mobilebackend.config;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Configuration
public class FeignConfig {

    private static final String CORRELATION_ID = "X-Correlation-ID";

    @Bean
    public RequestInterceptor requestForwardingInterceptor() {

        return template -> {

            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();

            if (attributes != null) {

                HttpServletRequest request = attributes.getRequest();

                // Forward Authorization header
                String authorization =
                        request.getHeader("Authorization");

                if (authorization != null && !authorization.isBlank()) {
                    template.header("Authorization", authorization);
                }

                // Forward Correlation ID
                String correlationId =
                        MDC.get("correlationId");

                if (correlationId == null || correlationId.isBlank()) {
                    correlationId =
                            request.getHeader(CORRELATION_ID);
                }

                if (correlationId != null && !correlationId.isBlank()) {
                    template.header(
                            CORRELATION_ID,
                            correlationId
                    );
                }
            }
        };
    }
}