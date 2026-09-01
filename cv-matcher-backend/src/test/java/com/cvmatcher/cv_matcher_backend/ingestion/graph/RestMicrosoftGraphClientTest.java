package com.cvmatcher.cv_matcher_backend.ingestion.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cvmatcher.cv_matcher_backend.microsoft.service.MicrosoftOAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RestMicrosoftGraphClientTest {
    private final MicrosoftOAuthService oauth = mock(MicrosoftOAuthService.class);

    @Test
    void followsMessageAndAttachmentPagesUsingMinimalDiscoveryData() {
        when(oauth.accessToken()).thenReturn("test-access-token");
        List<URI> requests = new ArrayList<>();
        GraphHttpTransport transport = (uri, ignored) -> {
            requests.add(uri);
            String path = uri.toString();
            if (path.contains("mailFolders")) return response("""
                    {"value":[{"id":"immutable-1","receivedDateTime":"2026-08-01T00:00:00Z","hasAttachments":true}],
                    "@odata.nextLink":"https://graph.test/messages-page-2"}""");
            if (path.contains("attachments-page-2")) return response("""
                    {"value":[{"id":"attachment-2","name":"cv.docx","contentType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","isInline":false,"contentBytes":"ZG9jeA=="}]}""");
            if (path.contains("attachments")) return response("""
                    {"value":[{"id":"attachment-1","name":"cv.pdf","contentType":"application/pdf","isInline":false,"contentBytes":"Y3Y="}],
                    "@odata.nextLink":"https://graph.test/attachments-page-2"}""");
            return response("{\"value\":[{\"id\":\"immutable-2\",\"receivedDateTime\":\"2026-08-02T00:00:00Z\",\"hasAttachments\":false}]}");
        };

        var result = client(transport).discoverInboxMessages(instant("2026-08-01T00:00:00Z"), instant("2026-08-31T23:59:59Z"));

        assertThat(result.acceptedMessageCount()).isEqualTo(2);
        assertThat(result.acceptedAttachmentCount()).isEqualTo(2);
        assertThat(result.acceptedAttachmentBytes()).isEqualTo(6);
        assertThat(result.truncated()).isFalse();
        assertThat(requests).hasSize(4);
        assertThat(requests.getFirst().toString()).contains("$select=id,receivedDateTime,hasAttachments")
                .contains("receivedDateTime+ge+2026-08-01T00%3A00%3A00Z")
                .contains("receivedDateTime+le+2026-08-31T23%3A59%3A59Z")
                .doesNotContain("subject").doesNotContain("body");
    }

    @Test
    void retriesRateLimitedResponseAndHonorsZeroRetryAfter() {
        when(oauth.accessToken()).thenReturn("test-access-token");
        int[] calls = {0};
        GraphHttpTransport transport = (uri, ignored) -> ++calls[0] == 1
                ? new GraphHttpTransport.GraphHttpResponse(429, "", Optional.of("0"))
                : response("{\"value\":[]}");

        var result = client(transport).discoverInboxMessages(instant("2026-08-01T00:00:00Z"), instant("2026-08-01T00:00:00Z"));

        assertThat(calls[0]).isEqualTo(2);
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void stopsAfterThreeTransientFailures() {
        when(oauth.accessToken()).thenReturn("test-access-token");
        int[] calls = {0};
        GraphHttpTransport transport = (uri, ignored) -> { calls[0]++; return new GraphHttpTransport.GraphHttpResponse(503, "", Optional.empty()); };

        assertThatThrownBy(() -> client(transport).discoverInboxMessages(instant("2026-08-01T00:00:00Z"), instant("2026-08-01T00:00:00Z")))
                .isInstanceOf(MicrosoftGraphTransientException.class);
        assertThat(calls[0]).isEqualTo(3);
    }

    @Test
    void doesNotRetryNonTransientGraphFailure() {
        when(oauth.accessToken()).thenReturn("test-access-token");
        int[] calls = {0};
        GraphHttpTransport transport = (uri, ignored) -> { calls[0]++; return new GraphHttpTransport.GraphHttpResponse(400, "", Optional.empty()); };

        assertThatThrownBy(() -> client(transport).discoverInboxMessages(instant("2026-08-01T00:00:00Z"), instant("2026-08-01T00:00:00Z")))
                .isInstanceOf(MicrosoftGraphRequestException.class);
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void marksResultTruncatedBeforeAcceptingAttachmentBeyondConfiguredLimit() {
        when(oauth.accessToken()).thenReturn("test-access-token");
        GraphHttpTransport transport = (uri, ignored) -> uri.toString().contains("mailFolders")
                ? response("{\"value\":[{\"id\":\"immutable-1\",\"receivedDateTime\":\"2026-08-01T00:00:00Z\",\"hasAttachments\":true}]}")
                : response("{\"value\":[{\"id\":\"attachment-1\",\"name\":\"cv.pdf\",\"contentType\":\"application/pdf\",\"isInline\":false,\"contentBytes\":\"YWI=\"}]}");

        var result = client(transport, new RestMicrosoftGraphClient.Limits(1, 1, 1))
                .discoverInboxMessages(instant("2026-08-01T00:00:00Z"), instant("2026-08-01T00:00:00Z"));

        assertThat(result.truncated()).isTrue();
        assertThat(result.messages()).isEmpty();
        assertThat(result.acceptedAttachmentBytes()).isZero();
    }

    @Test
    void marksMessageAndAttachmentLimitsAsTruncated() {
        when(oauth.accessToken()).thenReturn("test-access-token");
        GraphHttpTransport messages = (uri, ignored) -> response("""
                {"value":[
                  {"id":"immutable-1","receivedDateTime":"2026-08-01T00:00:00Z","hasAttachments":false},
                  {"id":"immutable-2","receivedDateTime":"2026-08-02T00:00:00Z","hasAttachments":false}
                ]}""");
        assertThat(client(messages, new RestMicrosoftGraphClient.Limits(1, 10, 10))
                .discoverInboxMessages(instant("2026-08-01T00:00:00Z"), instant("2026-08-02T00:00:00Z")).truncated()).isTrue();

        GraphHttpTransport attachments = (uri, ignored) -> uri.toString().contains("mailFolders")
                ? response("{\"value\":[{\"id\":\"immutable-1\",\"receivedDateTime\":\"2026-08-01T00:00:00Z\",\"hasAttachments\":true}]}")
                : response("{\"value\":[{\"id\":\"attachment-1\",\"name\":\"one.pdf\",\"contentType\":\"application/pdf\",\"isInline\":false,\"contentBytes\":\"YQ==\"},{\"id\":\"attachment-2\",\"name\":\"two.pdf\",\"contentType\":\"application/pdf\",\"isInline\":false,\"contentBytes\":\"Yg==\"}]}");
        assertThat(client(attachments, new RestMicrosoftGraphClient.Limits(10, 1, 10))
                .discoverInboxMessages(instant("2026-08-01T00:00:00Z"), instant("2026-08-01T00:00:00Z")).truncated()).isTrue();
    }

    private RestMicrosoftGraphClient client(GraphHttpTransport transport) {
        return client(transport, RestMicrosoftGraphClient.DEFAULT_LIMITS);
    }

    private RestMicrosoftGraphClient client(GraphHttpTransport transport, RestMicrosoftGraphClient.Limits limits) {
        return new RestMicrosoftGraphClient(new ObjectMapper(), oauth, transport, "https://graph.test/v1.0", limits);
    }

    private GraphHttpTransport.GraphHttpResponse response(String body) {
        return new GraphHttpTransport.GraphHttpResponse(200, body, Optional.empty());
    }

    private Instant instant(String value) { return Instant.parse(value); }
}
