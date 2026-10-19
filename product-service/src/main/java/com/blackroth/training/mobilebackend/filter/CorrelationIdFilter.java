package com.blackroth.training.mobilebackend.filter;

import com.blackroth.training.mobilebackend.util.CorrelationIdUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String rawCorrelationId = request.getHeader(CorrelationIdUtils.CORRELATION_ID_HEADER);
        String correlationId = CorrelationIdUtils.resolveCorrelationId(rawCorrelationId);

        try {
            MDC.put(CorrelationIdUtils.MDC_KEY, correlationId);

            response.setHeader(CorrelationIdUtils.CORRELATION_ID_HEADER, correlationId);

            filterChain.doFilter(request, response);

        } finally {
            MDC.remove(CorrelationIdUtils.MDC_KEY);
        }
    }
}