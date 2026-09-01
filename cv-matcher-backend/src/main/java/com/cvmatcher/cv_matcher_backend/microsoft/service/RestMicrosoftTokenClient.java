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
            return tokenResponse(body);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Microsoft token exchange failed", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Microsoft token exchange failed", exception);
        }
    }

    @Override
    public MicrosoftTokenResponse refreshAccessToken(String refreshToken) {
        String form = "client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
                + "&grant_type=refresh_token&refresh_token=" + encode(refreshToken)
                + "&scope=" + encode("User.Read Mail.Read offline_access");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://login.microsoftonline.com/consumers/oauth2/v2.0/token"))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 400 || response.statusCode() == 401) throw new MicrosoftReauthorizationRequiredException("Microsoft authorization is invalid");
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new MicrosoftTokenTransientException("Microsoft token refresh failed");
            return refreshTokenResponse(objectMapper.readValue(response.body(), Map.class));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new MicrosoftTokenTransientException("Microsoft token refresh interrupted", exception);
        } catch (java.io.IOException exception) { throw new MicrosoftTokenTransientException("Microsoft token refresh failed", exception); }
    }

    private MicrosoftTokenResponse tokenResponse(Map<String, Object> body) {
        String accessToken = body == null ? null : (String) body.get("access_token");
        String refreshToken = body == null ? null : (String) body.get("refresh_token");
        if (accessToken == null || accessToken.isBlank() || refreshToken == null || refreshToken.isBlank())
            throw new IllegalStateException("Microsoft token response was incomplete");
        return new MicrosoftTokenResponse(accessToken, refreshToken);
    }

    private MicrosoftTokenResponse refreshTokenResponse(Map<String, Object> body) {
        String accessToken = body == null ? null : (String) body.get("access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new MicrosoftTokenTransientException("Microsoft token response was incomplete");
        }
        String refreshToken = (String) body.get("refresh_token");
        return new MicrosoftTokenResponse(accessToken, refreshToken);
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
