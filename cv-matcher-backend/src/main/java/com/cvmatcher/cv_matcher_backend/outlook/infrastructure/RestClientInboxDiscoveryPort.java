package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import com.cvmatcher.cv_matcher_backend.job.JobDiscoveryProperties;
import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.application.InboxDiscoveryException;
import com.cvmatcher.cv_matcher_backend.outlook.application.InboxDiscoveryPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookAccessTokenPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookInboxAuthorizationPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookException;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
final class RestClientInboxDiscoveryPort implements InboxDiscoveryPort {
    private static final String INBOX_MESSAGES_PATH = "/v1.0/me/mailFolders/inbox/messages";
    private static final String IMMUTABLE_ID_HEADER = "IdType=\"ImmutableId\"";
    private final RestClient client;
    private final URI graphBaseUri;
    private final OutlookAccessTokenPort tokens;
    private final OutlookInboxAuthorizationPort authorization;
    private final JobDiscoveryProperties properties;
    private final MeterRegistry metrics;

    RestClientInboxDiscoveryPort(OutlookProperties outlook, OutlookAccessTokenPort tokens, OutlookInboxAuthorizationPort authorization, JobDiscoveryProperties properties, MeterRegistry metrics) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.graphBaseUri = URI.create(outlook.graphBaseUri());
        this.client = RestClient.builder().baseUrl(graphBaseUri.toString()).requestFactory(factory).build();
        this.tokens = tokens;
        this.authorization = authorization;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public Page listInboxMessages(Instant fromUtc, Instant toUtcExclusive, String nextLink, int pageSize) {
        return listInboxMessages(fromUtc, toUtcExclusive, nextLink, pageSize, () -> { });
    }

    @Override
    public Page listInboxMessages(Instant fromUtc, Instant toUtcExclusive, String nextLink, int pageSize, Runnable beforeRequest) {
        try {
            authorization.requireInboxReadBasic();
            var uri = nextLink == null ? initialUri(fromUtc, toUtcExclusive, pageSize) : validatedNextLink(nextLink, fromUtc, toUtcExclusive, pageSize);
            for (var attempt = 1; attempt <= properties.maxRetries(); attempt++) {
                try {
                beforeRequest.run();
                var response = client.get().uri(uri).header("Authorization", "Bearer " + tokens.accessToken().value())
                        .header("Prefer", IMMUTABLE_ID_HEADER).retrieve().body(GraphPage.class);
                if (response == null || response.value == null) throw new InboxDiscoveryException(InboxDiscoveryException.Kind.PROTOCOL_ERROR);
                var messages = response.value.stream().map(message -> {
                    if (message.id == null || message.receivedDateTime == null || message.hasAttachments == null)
                        throw new InboxDiscoveryException(InboxDiscoveryException.Kind.PROTOCOL_ERROR);
                    return new Message(message.id, message.receivedDateTime, message.hasAttachments);
                }).toList();
                return new Page(messages, response.nextLink == null ? null : validatedNextLink(response.nextLink, fromUtc, toUtcExclusive, pageSize).toString());
                } catch (RestClientResponseException exception) {
                if (exception.getStatusCode().value() == 401 || exception.getStatusCode().value() == 403)
                    throw new InboxDiscoveryException(InboxDiscoveryException.Kind.REAUTHORIZATION_REQUIRED);
                if (exception.getStatusCode().value() == 429) metrics.counter("outlook.graph_requests", "outcome", "rate_limited").increment();
                if ((exception.getStatusCode().value() == 429 || exception.getStatusCode().is5xxServerError()) && attempt < properties.maxRetries()) {
                    var headers = exception.getResponseHeaders();
                    sleep(headers == null ? null : headers.getFirst("Retry-After"), attempt, beforeRequest);
                    continue;
                }
                throw new InboxDiscoveryException(exception.getStatusCode().value() == 429 || exception.getStatusCode().is5xxServerError()
                        ? InboxDiscoveryException.Kind.TEMPORARY_FAILURE : InboxDiscoveryException.Kind.PROTOCOL_ERROR);
                } catch (InboxDiscoveryException | OutlookException exception) { throw exception;
                } catch (RuntimeException exception) {
                    if (attempt == properties.maxRetries()) throw new InboxDiscoveryException(InboxDiscoveryException.Kind.TEMPORARY_FAILURE);
                    sleep(null, attempt, beforeRequest);
                }
            }
            throw new InboxDiscoveryException(InboxDiscoveryException.Kind.TEMPORARY_FAILURE);
        } catch (OutlookException exception) {
            throw new InboxDiscoveryException("OUTLOOK_REAUTHORIZATION_REQUIRED".equals(exception.code())
                    ? InboxDiscoveryException.Kind.REAUTHORIZATION_REQUIRED : InboxDiscoveryException.Kind.TEMPORARY_FAILURE);
        }
    }

    private URI initialUri(Instant from, Instant to, int pageSize) {
        return UriComponentsBuilder.fromUri(graphBaseUri).path(INBOX_MESSAGES_PATH)
                .queryParam("$filter", "receivedDateTime ge " + from + " and receivedDateTime lt " + to)
                .queryParam("$orderby", "receivedDateTime asc").queryParam("$top", Math.min(50, pageSize))
                .queryParam("$select", "id,receivedDateTime,hasAttachments").build().encode().toUri();
    }

    private URI validatedNextLink(String value, Instant from, Instant to, int pageSize) {
        try {
            var uri = URI.create(value);
            if (!sameOrigin(uri) || !INBOX_MESSAGES_PATH.equals(uri.getPath()) || uri.getRawQuery() == null
                    || uri.getFragment() != null || uri.getUserInfo() != null || !validContinuationQuery(uri, from, to, pageSize)) {
                throw new InboxDiscoveryException(InboxDiscoveryException.Kind.PROTOCOL_ERROR);
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new InboxDiscoveryException(InboxDiscoveryException.Kind.PROTOCOL_ERROR);
        }
    }

    private boolean sameOrigin(URI uri) {
        return graphBaseUri.getScheme().equalsIgnoreCase(uri.getScheme())
                && graphBaseUri.getHost().equalsIgnoreCase(uri.getHost())
                && effectivePort(graphBaseUri) == effectivePort(uri);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static boolean validContinuationQuery(URI uri, Instant from, Instant to, int pageSize) {
        var query = decodedQuery(uri.getRawQuery());
        if (query.getOrDefault("$skiptoken", List.of()).size() != 1 || query.get("$skiptoken").getFirst().isBlank()) return false;
        if (query.size() == 1) return true;
        return query.size() == 5
                && List.of("receivedDateTime ge " + from + " and receivedDateTime lt " + to).equals(query.get("$filter"))
                && List.of("receivedDateTime asc").equals(query.get("$orderby"))
                && List.of("id,receivedDateTime,hasAttachments").equals(query.get("$select"))
                && List.of(String.valueOf(Math.min(50, pageSize))).equals(query.get("$top"));
    }

    private static Map<String, List<String>> decodedQuery(String rawQuery) {
        var query = new LinkedHashMap<String, List<String>>();
        for (var pair : rawQuery.split("&", -1)) {
            var separator = pair.indexOf('=');
            var key = URLDecoder.decode(separator < 0 ? pair : pair.substring(0, separator), StandardCharsets.UTF_8);
            var value = URLDecoder.decode(separator < 0 ? "" : pair.substring(separator + 1), StandardCharsets.UTF_8);
            query.merge(key, List.of(value), (left, right) -> { var values = new java.util.ArrayList<>(left); values.addAll(right); return values; });
        }
        return query;
    }

    private static void sleep(String retryAfter, int attempt, Runnable beforeRequest) {
        try {
            var remaining = retryAfterMillis(retryAfter, attempt);
            while (remaining > 0) {
                var wait = Math.min(1_000L, remaining);
                Thread.sleep(wait);
                remaining -= wait;
                beforeRequest.run();
            }
        }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new InboxDiscoveryException(InboxDiscoveryException.Kind.TEMPORARY_FAILURE); }
    }

    static long retryAfterMillis(String retryAfter, int attempt) {
        var fallback = attempt * 200L;
        if (retryAfter == null || retryAfter.isBlank()) return fallback;
        try {
            var seconds = Long.parseLong(retryAfter);
            return seconds > 0 ? Math.multiplyExact(seconds, 1_000L) : fallback;
        } catch (NumberFormatException ignored) {
            try {
                var remaining = java.time.Duration.between(Instant.now(), ZonedDateTime
                        .parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
                return remaining > 0 ? remaining : fallback;
            } catch (RuntimeException invalidDate) {
                return fallback;
            }
        } catch (ArithmeticException overflow) {
            return fallback;
        }
    }

    private record GraphPage(@JsonProperty("value") List<GraphMessage> value, @JsonProperty("@odata.nextLink") String nextLink) {}
    private record GraphMessage(String id, Instant receivedDateTime, Boolean hasAttachments) {}
}
