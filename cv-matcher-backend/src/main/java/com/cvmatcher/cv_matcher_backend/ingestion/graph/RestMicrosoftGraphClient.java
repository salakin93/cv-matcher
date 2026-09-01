package com.cvmatcher.cv_matcher_backend.ingestion.graph;

import com.cvmatcher.cv_matcher_backend.microsoft.service.MicrosoftOAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class RestMicrosoftGraphClient implements MicrosoftGraphClient {
    static final Limits DEFAULT_LIMITS = new Limits(500, 1_000, 1024L * 1024 * 1024);
    private static final int MAX_ATTEMPTS = 3;

    private final ObjectMapper mapper;
    private final MicrosoftOAuthService oauth;
    private final GraphHttpTransport transport;
    private final String graphBaseUrl;
    private final Limits limits;

    @Autowired
    RestMicrosoftGraphClient(ObjectMapper mapper, MicrosoftOAuthService oauth, GraphHttpTransport transport,
            @Value("${MICROSOFT_GRAPH_BASE_URL:https://graph.microsoft.com/v1.0}") String graphBaseUrl) {
        this.mapper = mapper;
        this.oauth = oauth;
        this.transport = transport;
        this.graphBaseUrl = graphBaseUrl.replaceAll("/+$", "");
        this.limits = DEFAULT_LIMITS;
    }

    RestMicrosoftGraphClient(ObjectMapper mapper, MicrosoftOAuthService oauth, GraphHttpTransport transport,
            String graphBaseUrl, Limits limits) {
        this.mapper = mapper;
        this.oauth = oauth;
        this.transport = transport;
        this.graphBaseUrl = graphBaseUrl.replaceAll("/+$", "");
        this.limits = limits;
    }

    @Override
    public DiscoveryResult discoverInboxMessages(Instant fromInclusive, Instant toInclusive) {
        if (fromInclusive == null || toInclusive == null || fromInclusive.isAfter(toInclusive)) {
            throw new IllegalArgumentException("The Graph discovery range is invalid");
        }
        List<GraphMessage> messages = new ArrayList<>();
        Counters counters = new Counters();
        String nextUrl = messagesUrl(fromInclusive, toInclusive);
        while (nextUrl != null) {
            JsonNode page = get(nextUrl);
            for (JsonNode message : page.path("value")) {
                if (messages.size() == limits.maxMessages()) return result(messages, counters, true);
                GraphMessage candidate = message(message);
                long candidateBytes = candidate.attachments().stream().mapToLong(file -> file.content().length).sum();
                if (counters.attachments + candidate.attachments().size() > limits.maxAttachments()
                        || counters.bytes + candidateBytes > limits.maxBytes()) return result(messages, counters, true);
                messages.add(candidate);
                counters.attachments += candidate.attachments().size();
                counters.bytes += candidateBytes;
            }
            nextUrl = nextLink(page);
        }
        return result(messages, counters, false);
    }

    private GraphMessage message(JsonNode message) {
        String id = requiredText(message, "id");
        Instant receivedAt = Instant.parse(requiredText(message, "receivedDateTime"));
        List<GraphAttachment> attachments = message.path("hasAttachments").asBoolean() ? attachments(id) : List.of();
        return new GraphMessage(id, receivedAt, attachments);
    }

    private List<GraphAttachment> attachments(String messageId) {
        List<GraphAttachment> result = new ArrayList<>();
        String nextUrl = graphBaseUrl + "/me/messages/" + encode(messageId)
                + "/attachments?$select=id,name,contentType,isInline,contentBytes";
        while (nextUrl != null) {
            JsonNode page = get(nextUrl);
            for (JsonNode attachment : page.path("value")) {
                if (attachment.hasNonNull("contentBytes")) {
                    result.add(new GraphAttachment(requiredText(attachment, "id"), attachment.path("name").asText(),
                            attachment.path("contentType").asText(), attachment.path("isInline").asBoolean(),
                            decode(attachment.path("contentBytes").asText())));
                }
            }
            nextUrl = nextLink(page);
        }
        return result;
    }

    private JsonNode get(String url) {
        MicrosoftGraphTransientException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                GraphHttpTransport.GraphHttpResponse response = transport.get(URI.create(url), oauth.accessToken());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return mapper.readTree(response.body());
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    failure = new MicrosoftGraphTransientException("Microsoft Graph is temporarily unavailable");
                    if (attempt < MAX_ATTEMPTS) pause(response.retryAfter().orElse(null));
                    continue;
                }
                throw new MicrosoftGraphRequestException("Microsoft Graph request failed");
            } catch (MicrosoftGraphTransientException exception) {
                failure = exception;
                if (attempt < MAX_ATTEMPTS) pause(null);
            } catch (java.io.IOException exception) {
                failure = new MicrosoftGraphTransientException("Microsoft Graph response was invalid", exception);
                if (attempt < MAX_ATTEMPTS) pause(null);
            }
        }
        throw failure == null ? new MicrosoftGraphTransientException("Microsoft Graph request failed") : failure;
    }

    private String messagesUrl(Instant from, Instant to) {
        String filter = "receivedDateTime ge " + DateTimeFormatter.ISO_INSTANT.format(from)
                + " and receivedDateTime le " + DateTimeFormatter.ISO_INSTANT.format(to);
        return graphBaseUrl + "/me/mailFolders/inbox/messages?$select=id,receivedDateTime,hasAttachments"
                + "&$orderby=receivedDateTime%20asc&$filter=" + encode(filter);
    }

    private DiscoveryResult result(List<GraphMessage> messages, Counters counters, boolean truncated) {
        return new DiscoveryResult(List.copyOf(messages), messages.size(), counters.attachments, counters.bytes, truncated);
    }

    private String nextLink(JsonNode page) {
        return page.hasNonNull("@odata.nextLink") && !page.path("@odata.nextLink").asText().isBlank()
                ? page.path("@odata.nextLink").asText() : null;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new MicrosoftGraphTransientException("Microsoft Graph response was incomplete");
        return value;
    }

    private byte[] decode(String content) {
        try { return Base64.getDecoder().decode(content); }
        catch (IllegalArgumentException exception) { throw new MicrosoftGraphTransientException("Microsoft Graph attachment was invalid", exception); }
    }

    private void pause(String retryAfter) {
        long delayMillis = parseRetryAfter(retryAfter);
        try { Thread.sleep(delayMillis); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MicrosoftGraphTransientException("Graph retry interrupted", exception);
        }
    }

    private long parseRetryAfter(String retryAfter) {
        try { return Math.min(Math.max(0, Long.parseLong(retryAfter)) * 1_000L, 30_000L); }
        catch (RuntimeException ignored) { return 250L; }
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static final class Counters { int attachments; long bytes; }
    record Limits(int maxMessages, int maxAttachments, long maxBytes) {
        Limits {
            if (maxMessages < 1 || maxAttachments < 1 || maxBytes < 1) throw new IllegalArgumentException("Graph limits must be positive");
        }
    }
}
