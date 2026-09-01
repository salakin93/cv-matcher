package com.cvmatcher.cv_matcher_backend.ingestion.graph;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class JdkGraphHttpTransport implements GraphHttpTransport {
    private final HttpClient client;

    JdkGraphHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    JdkGraphHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public GraphHttpResponse get(URI uri, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Prefer", "IdType=\"ImmutableId\"")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new GraphHttpResponse(
                    response.statusCode(), response.body(), response.headers().firstValue("Retry-After"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MicrosoftGraphTransientException("Graph request interrupted", exception);
        } catch (IOException exception) {
            throw new MicrosoftGraphTransientException("Graph request failed", exception);
        }
    }
}
