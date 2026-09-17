package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import com.cvmatcher.cv_matcher_backend.job.JobDiscoveryProperties;
import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.application.InboxDiscoveryException;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookAccessTokenPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookInboxAuthorizationPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookException;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class RestClientInboxDiscoveryPortTest {
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void sendsOnlyTheRequiredInitialInboxQuery() throws Exception {
        var requestedUri = new AtomicReference<String>();
        var server = server(requestedUri);
        try {
            port(server).listInboxMessages(FROM, TO, null, 50);
            var query = UriComponentsBuilder.fromUriString(requestedUri.get()).build().getQueryParams();
            assertEquals("receivedDateTime ge " + FROM + " and receivedDateTime lt " + TO, decode(query.getFirst("$filter")));
            assertEquals("receivedDateTime asc", decode(query.getFirst("$orderby")));
            assertEquals("id,receivedDateTime,hasAttachments", decode(query.getFirst("$select")));
            assertEquals("50", query.getFirst("$top"));
            assertEquals(4, query.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsContinuationQueriesThatPreserveTheOriginalDiscoveryParameters() throws Exception {
        var server = server(new AtomicReference<>());
        try {
            port(server).listInboxMessages(FROM, TO, continuationWithOriginalQuery(base(server)), 50);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsContinuationWithOnlyANonblankSkipToken() throws Exception {
        var requestedUri = new AtomicReference<String>();
        var server = server(requestedUri);
        try {
            var continuation = continuation(base(server));
            port(server).listInboxMessages(FROM, TO, continuation, 50);
            assertEquals(continuation, requestedUri.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesOnlyOneNonblankSkipTokenQueryParameter() {
        var base = "https://graph.example.test/v1.0/me/mailFolders/inbox/messages";

        assertTrue(RestClientInboxDiscoveryPort.validContinuationQuery(URI.create(base + "?$skiptoken=opaque-token"), FROM, TO, 50));
        assertFalse(RestClientInboxDiscoveryPort.validContinuationQuery(URI.create(base + "?$skiptoken=%20%20"), FROM, TO, 50));
        assertFalse(RestClientInboxDiscoveryPort.validContinuationQuery(URI.create(base + "?$skiptoken=opaque-token&$filter=receivedDateTime"), FROM, TO, 50));
        assertFalse(RestClientInboxDiscoveryPort.validContinuationQuery(URI.create(base + "?$skiptoken=first&$skiptoken=second"), FROM, TO, 50));
    }

    @Test
    void rejectsUnsafeDiscoveryConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new JobDiscoveryProperties(true, Duration.ZERO, Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 3, 50, 5000));
        assertThrows(IllegalArgumentException.class, () -> new JobDiscoveryProperties(true, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 3, 50, 5000));
        assertThrows(IllegalArgumentException.class, () -> new JobDiscoveryProperties(true, Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 4, 50, 5000));
    }

    @Test
    void parsesRetryAfterSecondsHttpDateAndInvalidValuesSafely() {
        assertEquals(2_000L, RestClientInboxDiscoveryPort.retryAfterMillis("2", 1));
        assertEquals(200L, RestClientInboxDiscoveryPort.retryAfterMillis(null, 1));
        assertEquals(200L, RestClientInboxDiscoveryPort.retryAfterMillis("invalid", 1));
        var expired = ZonedDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(2).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(200L, RestClientInboxDiscoveryPort.retryAfterMillis(expired, 1));
        var future = ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(2).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertTrue(RestClientInboxDiscoveryPort.retryAfterMillis(future, 1) > 0);
    }

    @Test
    void retriesRateLimitedResponsesWithoutHeadersAndEmitsTheRateLimitedMetric() throws Exception {
        var requests = new AtomicInteger();
        var registry = new SimpleMeterRegistry();
        var server = errorServer(429, null, requests);
        try {
            var exception = assertThrows(InboxDiscoveryException.class,
                    () -> port(server, registry).listInboxMessages(FROM, TO, null, 50));
            assertEquals(InboxDiscoveryException.Kind.TEMPORARY_FAILURE, exception.kind());
            assertEquals(3, requests.get());
            assertEquals(3.0, registry.get("outlook.graph_requests").tag("outcome", "rate_limited").counter().count());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void renewsTheLeaseWhileWaitingForRetryAfter() throws Exception {
        var requests = new AtomicInteger();
        var renewals = new AtomicInteger();
        var server = errorServer(429, "1", requests);
        try {
            assertThrows(InboxDiscoveryException.class,
                    () -> port(server, new SimpleMeterRegistry()).listInboxMessages(FROM, TO, null, 50, renewals::incrementAndGet));
            assertEquals(3, requests.get());
            assertTrue(renewals.get() >= 5);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsProviderFailuresToSafeDiscoveryKinds() throws Exception {
        var serverFailure = errorServer(503, null, new AtomicInteger());
        var unauthorized = errorServer(401, null, new AtomicInteger());
        var invalidPayload = invalidPayloadServer();
        try {
            assertEquals(InboxDiscoveryException.Kind.TEMPORARY_FAILURE, assertThrows(InboxDiscoveryException.class,
                    () -> port(serverFailure).listInboxMessages(FROM, TO, null, 50)).kind());
            assertEquals(InboxDiscoveryException.Kind.REAUTHORIZATION_REQUIRED, assertThrows(InboxDiscoveryException.class,
                    () -> port(unauthorized).listInboxMessages(FROM, TO, null, 50)).kind());
            assertEquals(InboxDiscoveryException.Kind.PROTOCOL_ERROR, assertThrows(InboxDiscoveryException.class,
                    () -> port(invalidPayload).listInboxMessages(FROM, TO, null, 50)).kind());
        } finally {
            serverFailure.stop(0);
            unauthorized.stop(0);
            invalidPayload.stop(0);
        }
    }

    @Test
    void exposesNoProviderDetailsThroughDiscoveryExceptions() throws Exception {
        var server = errorServer(503, null, new AtomicInteger());
        try {
            var exception = assertThrows(InboxDiscoveryException.class,
                    () -> port(server).listInboxMessages(FROM, TO, null, 50));
            var details = String.valueOf(exception);
            assertFalse(details.contains("test-token"));
            assertFalse(details.contains(base(server)));
            assertFalse(details.contains("payload"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsNetworkFailureAndMissingInboxScopeWithoutProviderRetries() {
        var network = port("http://127.0.0.1:1", mock(OutlookInboxAuthorizationPort.class), new SimpleMeterRegistry());
        assertEquals(InboxDiscoveryException.Kind.TEMPORARY_FAILURE, assertThrows(InboxDiscoveryException.class,
                () -> network.listInboxMessages(FROM, TO, null, 50)).kind());

        var authorization = mock(OutlookInboxAuthorizationPort.class);
        doThrow(new OutlookException(HttpStatus.UNAUTHORIZED, "OUTLOOK_REAUTHORIZATION_REQUIRED"))
                .when(authorization).requireInboxReadBasic();
        var scoped = port("http://127.0.0.1:1", authorization, new SimpleMeterRegistry());
        assertEquals(InboxDiscoveryException.Kind.REAUTHORIZATION_REQUIRED, assertThrows(InboxDiscoveryException.class,
                () -> scoped.listInboxMessages(FROM, TO, null, 50)).kind());
    }

    @Test
    void rejectsBlankOrAdditionalContinuationQueryParameters() throws Exception {
        var server = server(new AtomicReference<>());
        try {
            var base = base(server) + "/v1.0/me/mailFolders/inbox/messages";
            assertThrows(InboxDiscoveryException.class, () -> port(server).listInboxMessages(FROM, TO, base + "?$skiptoken=%20%20", 50));
            assertThrows(InboxDiscoveryException.class, () -> port(server).listInboxMessages(FROM, TO, base + "?$skiptoken=opaque-token&unexpected=value", 50));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsGraphNextLinkThatChangesTheOriginalDiscoveryParameters() throws Exception {
        var requestedUri = new AtomicReference<String>();
        var nextLink = new AtomicReference<String>();
        var requests = new AtomicInteger();
        var server = server(requestedUri, nextLink, requests);
        try {
            nextLink.set(UriComponentsBuilder.fromUriString(base(server)).path("/v1.0/me/mailFolders/inbox/messages")
                    .queryParam("$filter", "receivedDateTime ge " + FROM + " and receivedDateTime lt " + TO.plusSeconds(1))
                    .queryParam("$orderby", "receivedDateTime asc").queryParam("$select", "id,receivedDateTime,hasAttachments").queryParam("$top", "50")
                    .queryParam("$skiptoken", "opaque-token").build().encode().toUriString());

            assertThrows(InboxDiscoveryException.class, () -> port(server).listInboxMessages(FROM, TO, null, 50));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static RestClientInboxDiscoveryPort port(HttpServer server) {
        return port(server, new SimpleMeterRegistry());
    }

    private static RestClientInboxDiscoveryPort port(HttpServer server, SimpleMeterRegistry registry) {
        return port(base(server), mock(OutlookInboxAuthorizationPort.class), registry);
    }

    private static RestClientInboxDiscoveryPort port(String graphBaseUri, OutlookInboxAuthorizationPort authorization, SimpleMeterRegistry registry) {
        var tokens = mock(OutlookAccessTokenPort.class);
        when(tokens.accessToken()).thenReturn(new OutlookAccessTokenPort.AccessToken("test-token", Instant.now().plusSeconds(60)));
        return new RestClientInboxDiscoveryPort(new OutlookProperties(null, null, null, null, null, null, null, 1,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3, graphBaseUri), tokens, authorization,
                new JobDiscoveryProperties(true, Duration.ofSeconds(1), Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ofSeconds(1), 3, 50, 5000), registry);
    }

    private static String continuation(String base) {
        return UriComponentsBuilder.fromUriString(base).path("/v1.0/me/mailFolders/inbox/messages")
                .queryParam("$skiptoken", "opaque-token").build().encode().toUriString();
    }

    private static String continuationWithOriginalQuery(String base) {
        return UriComponentsBuilder.fromUriString(base).path("/v1.0/me/mailFolders/inbox/messages")
                .queryParam("$filter", "receivedDateTime ge " + FROM + " and receivedDateTime lt " + TO)
                .queryParam("$orderby", "receivedDateTime asc").queryParam("$select", "id,receivedDateTime,hasAttachments").queryParam("$top", "50")
                .queryParam("$skiptoken", "opaque-token").build().encode().toUriString();
    }

    private static HttpServer server(AtomicReference<String> requestedUri) throws Exception {
        return server(requestedUri, new AtomicReference<>(), new AtomicInteger());
    }

    private static HttpServer server(AtomicReference<String> requestedUri, AtomicReference<String> nextLink) throws Exception {
        return server(requestedUri, nextLink, new AtomicInteger());
    }

    private static HttpServer server(AtomicReference<String> requestedUri, AtomicReference<String> nextLink, AtomicInteger requests) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1.0/me/mailFolders/inbox/messages", exchange -> {
            requests.incrementAndGet();
            requestedUri.set("http://127.0.0.1:" + exchange.getLocalAddress().getPort() + exchange.getRequestURI());
            var body = (nextLink.get() == null ? "{\"value\":[]}" : "{\"value\":[],\"@odata.nextLink\":\"" + nextLink.get() + "\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer errorServer(int status, String retryAfter, AtomicInteger requests) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1.0/me/mailFolders/inbox/messages", exchange -> {
            requests.incrementAndGet();
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static HttpServer invalidPayloadServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1.0/me/mailFolders/inbox/messages", exchange -> {
            var body = "{\"value\":[{\"id\":\"message\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
