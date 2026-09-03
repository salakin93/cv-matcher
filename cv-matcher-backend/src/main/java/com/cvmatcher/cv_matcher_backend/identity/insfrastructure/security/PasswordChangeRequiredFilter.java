package com.cvmatcher.cv_matcher_backend.identity.insfrastructure.security;

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
import java.util.Map;

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
            objectMapper.writeValue(response.getOutputStream(), Map.of(
                    "status", HttpServletResponse.SC_FORBIDDEN,
                    "code", "PASSWORD_CHANGE_REQUIRED",
                    "message", "Debe cambiar su contraseña antes de continuar.",
                    "timestamp", Instant.now().toString(),
                    "path", request.getRequestURI()
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
