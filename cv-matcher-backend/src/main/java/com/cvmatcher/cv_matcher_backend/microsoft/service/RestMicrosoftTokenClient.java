package com.cvmatcher.cv_matcher_backend.microsoft.service;

import java.util.Map;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
class RestMicrosoftTokenClient implements MicrosoftTokenClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    RestMicrosoftTokenClient(
            ObjectMapper objectMapper,
            @Value("${MICROSOFT_CLIENT_ID:}") String clientId,
            @Value("${MICROSOFT_CLIENT_SECRET:}") String clientSecret,
            @Value("${MICROSOFT_REDIRECT_URI:http://localhost:8080/oauth2/callback/microsoft}") String redirectUri) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.objectMapper = objectMapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    @Override
    @SuppressWarnings("unchecked")
    public MicrosoftTokenResponse exchangeAuthorizationCode(String code, String codeVerifier) {
        String form = "client_id=" + encode(clientId)
                        + "&client_secret=" + encode(clientSecret)
                        + "&grant_type=authorization_code"
                        + "&code=" + encode(code)
                        + "&redirect_uri=" + encode(redirectUri)
                        + "&code_verifier=" + encode(codeVerifier);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Microsoft token exchange failed");
            }
            Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
            String refreshToken = body == null ? null : (String) body.get("refresh_token");
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new IllegalStateException("Microsoft token response did not contain a refresh token");
            }
            return new MicrosoftTokenResponse(refreshToken);
        } catch (java.io.IOException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Microsoft token exchange failed", exception);
        }
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
