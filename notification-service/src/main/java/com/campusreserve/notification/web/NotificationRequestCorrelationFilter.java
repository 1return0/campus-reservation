package com.campusreserve.notification.web;

import com.campusreserve.common.web.RequestCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationRequestCorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = RequestCorrelation.normalize(request.getHeader(RequestCorrelation.HEADER_NAME));
        response.setHeader(RequestCorrelation.HEADER_NAME, requestId);
        MDC.put(RequestCorrelation.MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestCorrelation.MDC_KEY);
        }
    }
}
