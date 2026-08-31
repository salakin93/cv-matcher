package com.cvmatcher.cv_matcher_backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminTokenAuthenticationFilter extends OncePerRequestFilter {

    private final byte[] expectedToken;

    public AdminTokenAuthenticationFilter(@Value("${admin.api-token:}") String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedToken = request.getHeader("X-Admin-Token");
        if (suppliedToken != null
                && expectedToken.length > 0
                && MessageDigest.isEqual(expectedToken, suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        }
        filterChain.doFilter(request, response);
    }
}
