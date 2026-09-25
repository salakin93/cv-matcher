package com.cvmatcher.cv_matcher_backend.identity.insfrastructure.security;

import com.cvmatcher.cv_matcher_backend.identity.SecurityProperties;
import com.cvmatcher.cv_matcher_backend.identity.api.ApiError;
import com.cvmatcher.cv_matcher_backend.identity.insfrastructure.observability.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(SecurityProperties properties) {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setHeaderName("X-CSRF-TOKEN");
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie
                .sameSite("Lax")
                .secure(properties.secureCookies()));
        return repository;
    }

    @Bean
    SecurityFilterChain security(
            HttpSecurity http,
            BearerJwtAuthenticationFilter bearer,
            CookieCsrfTokenRepository csrfTokenRepository,
            ObjectMapper mapper
    ) throws Exception {
        http.csrf(c -> c.csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(r -> HttpMethod.POST.name().equals(r.getMethod()) && ("/api/v1/auth/refresh".equals(r.getRequestURI()) || "/api/v1/auth/logout".equals(r.getRequestURI()))))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(
                                "/error",
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/password-reset/request",
                                "/api/v1/auth/password-reset/confirm",
                                "/api/v1/auth/email-change/verify",
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/auth/password/change").hasAnyRole("RECRUITER", "ADMIN", "PASSWORD_CHANGE_REQUIRED")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().hasAnyRole("RECRUITER", "ADMIN"))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, exception) ->
                                write(mapper, request, response, 401, "UNAUTHENTICATED", "No autenticado"))
                        .accessDeniedHandler((request, response, exception) ->
                                write(mapper, request, response, 403, "FORBIDDEN",
                                        "No tiene permisos para realizar esta operación.")))
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void write(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        var value = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        var correlationId = value instanceof UUID uuid ? uuid : null;
        mapper.writeValue(response.getOutputStream(), new ApiError(status, code, message, Instant.now(), request.getRequestURI(), correlationId));
    }
}
