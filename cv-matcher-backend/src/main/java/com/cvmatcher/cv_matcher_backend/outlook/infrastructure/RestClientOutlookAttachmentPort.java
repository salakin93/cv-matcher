package com.cvmatcher.cv_matcher_backend.outlook.infrastructure;

import com.cvmatcher.cv_matcher_backend.outlook.OutlookProperties;
import com.cvmatcher.cv_matcher_backend.outlook.application.AttachmentException;
import com.cvmatcher.cv_matcher_backend.outlook.application.InboxDiscoveryException;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookAccessTokenPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookAttachmentPort;
import com.cvmatcher.cv_matcher_backend.outlook.application.OutlookInboxAuthorizationPort;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Component
final class RestClientOutlookAttachmentPort implements OutlookAttachmentPort {
    private static final String PREFER_IMMUTABLE_ID = "IdType=\"ImmutableId\"";
    private final RestClient client;
    private final URI base;
    private final OutlookAccessTokenPort tokens;
    private final OutlookInboxAuthorizationPort authorization;
    private final int maxRetries;

    RestClientOutlookAttachmentPort(OutlookProperties properties, OutlookAccessTokenPort tokens, OutlookInboxAuthorizationPort authorization) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        this.base = URI.create(properties.graphBaseUri());
        this.client = RestClient.builder().baseUrl(base.toString()).requestFactory(factory).build();
        this.tokens = tokens;
        this.authorization = authorization;
        this.maxRetries = properties.maxRetries();
    }

    @Override
    public List<Attachment> listAttachments(String messageId, Runnable beforeRequest) {
        var uri = path(messageId, null, false).queryParam("$select", "id,size,isInline").build().encode().toUri();
        var page = request(uri, GraphPage.class, beforeRequest);
        if (page == null || page.value == null) throw new AttachmentException(AttachmentException.Kind.PROTOCOL_ERROR);
        return page.value.stream().map(value -> {
            if (value.id == null || value.size == null || value.isInline == null || value.type == null) throw new AttachmentException(AttachmentException.Kind.PROTOCOL_ERROR);
            return new Attachment(value.id, value.size, value.isInline, kind(value.type));
        }).toList();
    }

    @Override
    public byte[] download(String messageId, String attachmentId, Runnable beforeRequest) {
        var bytes = request(path(messageId, attachmentId, true).build().encode().toUri(), byte[].class, beforeRequest);
        if (bytes == null) throw new AttachmentException(AttachmentException.Kind.PROTOCOL_ERROR);
        return bytes;
    }

    private <T> T request(URI uri, Class<T> type, Runnable beforeRequest) {
        try {
            authorization.requireInboxReadBasic();
            for (var attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    beforeRequest.run();
                    return client.get().uri(uri).header("Authorization", "Bearer " + tokens.accessToken().value()).header("Prefer", PREFER_IMMUTABLE_ID).retrieve().body(type);
                } catch (RestClientResponseException exception) {
                    var status = exception.getStatusCode().value();
                    if (status == 401 || status == 403) throw new AttachmentException(AttachmentException.Kind.REAUTHORIZATION_REQUIRED);
                    if ((status == 429 || exception.getStatusCode().is5xxServerError()) && attempt < maxRetries) { waitForRetry(exception.getResponseHeaders() == null ? null : exception.getResponseHeaders().getFirst("Retry-After"), attempt, beforeRequest); continue; }
                    throw new AttachmentException(status == 429 || exception.getStatusCode().is5xxServerError() ? AttachmentException.Kind.TEMPORARY_FAILURE : AttachmentException.Kind.PROTOCOL_ERROR);
                }
            }
            throw new AttachmentException(AttachmentException.Kind.TEMPORARY_FAILURE);
        } catch (AttachmentException exception) { throw exception;
        } catch (InboxDiscoveryException exception) { throw new AttachmentException(exception.kind() == InboxDiscoveryException.Kind.REAUTHORIZATION_REQUIRED ? AttachmentException.Kind.REAUTHORIZATION_REQUIRED : AttachmentException.Kind.TEMPORARY_FAILURE);
        } catch (RuntimeException exception) { throw new AttachmentException(AttachmentException.Kind.TEMPORARY_FAILURE); }
    }

    private static void waitForRetry(String retryAfter, int attempt, Runnable beforeRequest) {
        try {
            var remaining = RestClientInboxDiscoveryPort.retryAfterMillis(retryAfter, attempt);
            while (remaining > 0) {
                var wait = Math.min(1_000L, remaining);
                Thread.sleep(wait);
                remaining -= wait;
                beforeRequest.run();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AttachmentException(AttachmentException.Kind.TEMPORARY_FAILURE);
        }
    }

    private UriComponentsBuilder path(String messageId, String attachmentId, boolean value) {
        var path = attachmentId == null ? "/v1.0/me/mailFolders/inbox/messages/{messageId}/attachments"
                : value ? "/v1.0/me/mailFolders/inbox/messages/{messageId}/attachments/{attachmentId}/$value"
                : "/v1.0/me/mailFolders/inbox/messages/{messageId}/attachments/{attachmentId}";
        var uri = attachmentId == null ? UriComponentsBuilder.fromUri(base).path(path).buildAndExpand(messageId).encode().toUri()
                : UriComponentsBuilder.fromUri(base).path(path).buildAndExpand(messageId, attachmentId).encode().toUri();
        return UriComponentsBuilder.fromUri(uri);
    }

    private static Attachment.Kind kind(String type) {
        return "#microsoft.graph.fileAttachment".equals(type) ? Attachment.Kind.FILE : "#microsoft.graph.itemAttachment".equals(type) ? Attachment.Kind.ITEM : Attachment.Kind.REFERENCE;
    }
    private record GraphPage(@JsonProperty("value") List<GraphAttachment> value) {}
    private record GraphAttachment(String id, Long size, Boolean isInline, @JsonProperty("@odata.type") String type) {}
}
