package com.cvmatcher.cv_matcher_backend.ingestion.graph;

import java.net.URI;
import java.util.Optional;

/** HTTP boundary for Microsoft Graph. It keeps transport concerns outside discovery logic. */
public interface GraphHttpTransport {
    GraphHttpResponse get(URI uri, String accessToken);

    record GraphHttpResponse(int statusCode, String body, Optional<String> retryAfter) {}
}
