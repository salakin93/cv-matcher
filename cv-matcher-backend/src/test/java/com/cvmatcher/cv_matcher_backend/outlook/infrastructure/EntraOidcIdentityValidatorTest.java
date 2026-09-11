package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntraOidcIdentityValidatorTest {
    @Test
    void discoveryTimeoutIsReportedAsSafeTemporaryFailure() throws Exception {
        try (DoubleServer server = server((exchange, issuer) -> {
            Thread.sleep(200);
            respond(exchange, 200, discovery(issuer));
        })) {
            var exception = assertThrows(OutlookException.class, () -> validator(server, Duration.ofMillis(25)).validate("not-a-jwt", new byte[32]));
            assertEquals("OUTLOOK_TEMPORARILY_UNAVAILABLE", exception.code());
        }
    }

    @Test
    void discoveryHttpFailureIsReportedAsSafeAuthorizationFailure() throws Exception {
        try (DoubleServer server = server((exchange, issuer) -> respond(exchange, 500, "{}"))) {
            var exception = assertThrows(OutlookException.class, () -> validator(server, Duration.ofSeconds(1)).validate("not-a-jwt", new byte[32]));
            assertEquals("OUTLOOK_AUTHORIZATION_FAILED", exception.code());
        }
    }

    @Test
    void jwksTimeoutIsReportedAsSafeTemporaryFailure() throws Exception {
        try (DoubleServer server = server((exchange, issuer) -> respond(exchange, 200, discovery(issuer)), exchange -> {
            Thread.sleep(200);
            respond(exchange, 200, "{\"keys\":[]}");
        })) {
            var exception = assertThrows(OutlookException.class, () -> validator(server, Duration.ofMillis(25)).validate(token(server.authority() + "/v2.0"), new byte[32]));
            assertEquals("OUTLOOK_TEMPORARILY_UNAVAILABLE", exception.code());
        }
    }

    @Test
    void discoveryIsPerformedOnceWhenTheDecoderIsReused() throws Exception {
        try (DoubleServer server = server((exchange, issuer) -> respond(exchange, 200, discovery(issuer)))) {
            var validator = validator(server, Duration.ofSeconds(1));
            assertThrows(JwtException.class, () -> validator.validate("not-a-jwt", new byte[32]));
            assertThrows(JwtException.class, () -> validator.validate("not-a-jwt", new byte[32]));
            assertEquals(1, server.requests.get());
        }
    }

    private static EntraOidcIdentityValidator validator(DoubleServer server, Duration timeout) {
        return new EntraOidcIdentityValidator(new OutlookProperties("tenant", "client", "secret", server.authority(), "http://localhost/callback", "http://localhost", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 1, timeout, timeout, 1));
    }

    private static DoubleServer server(DiscoveryHandler handler) throws IOException {
        return server(handler, exchange -> respond(exchange, 200, "{\"keys\":[]}"));
    }

    private static DoubleServer server(DiscoveryHandler handler, JwksHandler jwksHandler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        server.createContext("/v2.0/.well-known/openid-configuration", exchange -> {
            requests.incrementAndGet();
            try {
                handler.respond(exchange, "http://127.0.0.1:" + server.getAddress().getPort() + "/v2.0");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.createContext("/keys", exchange -> {
            try {
                jwksHandler.respond(exchange);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
        return new DoubleServer(server, requests);
    }

    private static String discovery(String issuer) {
        return "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer.substring(0, issuer.length() - 5) + "/keys\"}";
    }

    private static String token(String issuer) {
        return base64Url("{\"alg\":\"RS256\",\"kid\":\"test\"}") + "."
                + base64Url("{\"iss\":\"" + issuer + "\",\"aud\":\"client\",\"exp\":" + Instant.now().plusSeconds(300).getEpochSecond() + "}") + ".signature";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record DoubleServer(HttpServer server, AtomicInteger requests) implements AutoCloseable {
        String authority() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface DiscoveryHandler {
        void respond(com.sun.net.httpserver.HttpExchange exchange, String issuer) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    private interface JwksHandler {
        void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException, InterruptedException;
    }
}
