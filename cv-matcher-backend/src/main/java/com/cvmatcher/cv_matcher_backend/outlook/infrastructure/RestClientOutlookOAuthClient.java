package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
final class RestClientOutlookOAuthClient implements OutlookOAuthClient {
    private static final String AUTHORIZATION_PATH = "/oauth2/v2.0/authorize";
    private static final String TOKEN_PATH = "/oauth2/v2.0/token";
    private static final String SCOPES = "openid profile offline_access";
    private final RestClient client;
    private final OutlookProperties properties;

    RestClientOutlookOAuthClient(OutlookProperties properties) {
        this.properties = properties;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().baseUrl(properties.authority()).requestFactory(factory).build();
    }

    @Override
    public String authorizationUrl(String state, String nonce, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(properties.authority()).path(AUTHORIZATION_PATH)
                .queryParam("client_id", properties.clientId()).queryParam("response_type", "code")
                .queryParam("redirect_uri", properties.redirectUri()).queryParam("response_mode", "query")
                .queryParam("scope", SCOPES).queryParam("state", state).queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge).queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
    }

    @Override
    public TokenResponse exchangeAuthorizationCode(String code, String codeVerifier) {
        var form = baseForm("authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        return requestToken(form);
    }

    @Override
    public TokenResponse refreshAccessToken(String refreshToken) {
        var form = baseForm("refresh_token");
        form.add("refresh_token", refreshToken);
        return requestToken(form);
    }

    private LinkedMultiValueMap<String, String> baseForm(String grantType) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("grant_type", grantType);
        form.add("redirect_uri", properties.redirectUri());
        return form;
    }

    private TokenResponse requestToken(LinkedMultiValueMap<String, String> form) {
        for (int attempt = 1; attempt <= properties.maxRetries(); attempt++) {
            try {
                var response = client.post().uri(TOKEN_PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form).retrieve().body(MicrosoftTokenResponse.class);
                return response == null ? new TokenResponse(null, null, null, null, 0) : response.toTokenResponse();
            } catch (RestClientResponseException exception) {
                var failure = classify(exception);
                if (failure.kind() != Failure.Kind.TEMPORARILY_UNAVAILABLE || attempt == properties.maxRetries()) throw failure;
                pause(exception.getResponseHeaders().getFirst("Retry-After"), attempt);
            } catch (RuntimeException exception) {
                if (attempt == properties.maxRetries()) throw new Failure(Failure.Kind.TEMPORARILY_UNAVAILABLE);
                pause(null, attempt);
            }
        }
        throw new Failure(Failure.Kind.TEMPORARILY_UNAVAILABLE);
    }

    private static Failure classify(RestClientResponseException exception) {
        if (exception.getResponseBodyAsString().contains("invalid_grant")) return new Failure(Failure.Kind.INVALID_GRANT);
        HttpStatusCode status = exception.getStatusCode();
        return new Failure(status.value() == 429 || status.is5xxServerError()
                ? Failure.Kind.TEMPORARILY_UNAVAILABLE : Failure.Kind.AUTHORIZATION_FAILED);
    }

    private static void pause(String retryAfter, int attempt) {
        try {
            Thread.sleep(retryDelayMilliseconds(retryAfter, attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Failure(Failure.Kind.TEMPORARILY_UNAVAILABLE);
        }
    }

    private static long retryDelayMilliseconds(String retryAfter, int attempt) {
        if (retryAfter == null) return attempt * 200L;
        try {
            return Math.max(1L, Long.parseLong(retryAfter)) * 1000L;
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(0L, ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - Instant.now().toEpochMilli());
            } catch (RuntimeException ignoredAgain) {
                return attempt * 200L;
            }
        }
    }

    private record MicrosoftTokenResponse(String access_token, String refresh_token, String id_token, String scope, Long expires_in) {
        TokenResponse toTokenResponse() {
            return new TokenResponse(access_token, refresh_token, id_token, scope, expires_in == null ? 3600 : expires_in);
        }
    }
}
