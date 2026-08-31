package com.cvmatcher.cv_matcher_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    private final boolean apiDocumentationEnabled;

    public SecurityConfiguration(@Value("${springdoc.api-docs.enabled:true}") boolean apiDocumentationEnabled) {
        this.apiDocumentationEnabled = apiDocumentationEnabled;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AdminTokenAuthenticationFilter adminTokenAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http.addFilterBefore(adminTokenAuthenticationFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/actuator/health", "/actuator/info").permitAll();
                    if (apiDocumentationEnabled) {
                        authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    }
                    authorize.requestMatchers("/api/**").authenticated();
                    authorize.anyRequest().denyAll();
                })
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
