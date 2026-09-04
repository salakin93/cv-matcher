package com.cvmatcher.cv_matcher_backend.identity.insfrastructure.security;

import com.cvmatcher.cv_matcher_backend.identity.api.ApiError;
import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {
    private final ObjectMapper objectMapper;

    public PasswordChangeRequiredFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && Boolean.TRUE.equals(authentication.getDetails()) && !isAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            var value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
            var correlationId = value instanceof UUID uuid ? uuid : null;
            objectMapper.writeValue(response.getOutputStream(), new ApiError(
                    HttpServletResponse.SC_FORBIDDEN,
                    "PASSWORD_CHANGE_REQUIRED",
                    "Debe cambiar su contraseña antes de continuar.",
                    Instant.now(),
                    request.getRequestURI(),
                    correlationId
            ));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowed(HttpServletRequest request) {
        var method = request.getMethod();
        var path = request.getRequestURI();
        return ("GET".equals(method) && "/api/v1/auth/me".equals(path))
                || ("POST".equals(method) && "/api/v1/auth/password/change".equals(path))
                || ("POST".equals(method) && "/api/v1/auth/logout".equals(path));
    }
}
