package vn.edu.hsf302.lawrag.ingestion.service;

import java.net.URI;
import java.time.Instant;

/**
 * Adapter boundary for retrieving a source document. Implementations must apply
 * HTTPS/allowlist/SSRF/size/timeout checks before returning bytes.
 */
public interface DocumentFetcher {

    FetchedDocument fetch(URI url);

    record FetchedDocument(
            URI finalUrl,
            String contentType,
            byte[] body,
            Instant fetchedAt) {

        public FetchedDocument {
            if (finalUrl == null) {
                throw new IllegalArgumentException("finalUrl must not be null");
            }
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType must not be blank");
            }
            if (body == null || body.length == 0) {
                throw new IllegalArgumentException("document body must not be empty");
            }
            if (fetchedAt == null) {
                throw new IllegalArgumentException("fetchedAt must not be null");
            }
        }
    }
}
