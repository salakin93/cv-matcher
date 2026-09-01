package com.cvmatcher.cv_matcher_backend.ingestion.graph;

import com.cvmatcher.cv_matcher_backend.microsoft.service.MicrosoftOAuthService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class RestMicrosoftGraphClient implements MicrosoftGraphClient {
    private static final int MAX_MESSAGES = 500;
    private static final int MAX_ATTACHMENTS = 1000;
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final MicrosoftOAuthService oauth;
    RestMicrosoftGraphClient(ObjectMapper mapper, MicrosoftOAuthService oauth) { this.mapper = mapper; this.oauth = oauth; }

    @Override public List<GraphMessage> inboxMessages(Instant from, Instant to) {
        List<GraphMessage> messages = new ArrayList<>(); int attachments = 0; long bytes = 0;
        String url = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messages?$select=id,receivedDateTime,hasAttachments"
                + "&$orderby=receivedDateTime asc&$filter=" + encode("receivedDateTime ge " + from + " and receivedDateTime le " + to);
        while (url != null && messages.size() < MAX_MESSAGES && attachments < MAX_ATTACHMENTS && bytes < MAX_BYTES) {
            JsonNode page = get(url); url = page.path("@odata.nextLink").asText(null);
            for (JsonNode value : page.path("value")) {
                if (!value.path("hasAttachments").asBoolean()) { messages.add(new GraphMessage(value.path("id").asText(), Instant.parse(value.path("receivedDateTime").asText()), List.of())); continue; }
                List<GraphAttachment> files = attachments(value.path("id").asText());
                for (GraphAttachment file : files) { attachments++; bytes += file.content().length; if (attachments > MAX_ATTACHMENTS || bytes > MAX_BYTES) break; }
                messages.add(new GraphMessage(value.path("id").asText(), Instant.parse(value.path("receivedDateTime").asText()), files));
                if (messages.size() >= MAX_MESSAGES || attachments >= MAX_ATTACHMENTS || bytes >= MAX_BYTES) break;
            }
        }
        return messages;
    }
    private List<GraphAttachment> attachments(String messageId) {
        JsonNode page = get("https://graph.microsoft.com/v1.0/me/messages/" + encode(messageId) + "/attachments?$select=id,name,contentType,isInline,contentBytes");
        List<GraphAttachment> result = new ArrayList<>();
        for (JsonNode value : page.path("value")) if (value.has("contentBytes")) result.add(new GraphAttachment(value.path("id").asText(), value.path("name").asText(), value.path("contentType").asText(), value.path("isInline").asBoolean(), Base64.getDecoder().decode(value.path("contentBytes").asText())));
        return result;
    }
    private JsonNode get(String url) {
        try {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + oauth.accessToken()).header("Prefer", "IdType=\"ImmutableId\"").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) throw new MicrosoftGraphTransientException("Graph rate limit exceeded");
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new MicrosoftGraphTransientException("Graph request failed");
            return mapper.readTree(response.body());
        } catch (java.io.IOException exception) { throw new MicrosoftGraphTransientException("Graph request failed", exception); }
          catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new MicrosoftGraphTransientException("Graph request interrupted", exception); }
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
