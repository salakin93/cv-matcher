package com.cvmatcher.cv_matcher_backend.ingestion.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdkGraphHttpTransportTest {
    @Test
    @SuppressWarnings("unchecked")
    void sendsImmutableIdPreferenceAndAuthorizationWithoutLoggingThem() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"value\":[]}");
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        when(client.send(request.capture(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

        new JdkGraphHttpTransport(client).get(URI.create("https://graph.test/v1.0/me/messages"), "test-token");

        assertThat(request.getValue().headers().firstValue("Prefer")).contains("IdType=\"ImmutableId\"");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("Bearer test-token");
    }
}
