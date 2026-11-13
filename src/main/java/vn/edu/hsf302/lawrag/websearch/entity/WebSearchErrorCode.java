package vn.edu.hsf302.lawrag.websearch.entity;

/**
 * Stable operational codes stored on a job and in candidate/document audit rows.
 * Only transient codes are retried; terminal codes finalize without another
 * provider call (docs/decisions.md §6.2, plan §10.1 item 10).
 */
public enum WebSearchErrorCode {
    PROVIDER_RATE_LIMITED(true),
    PROVIDER_5XX(true),
    PROVIDER_TIMEOUT(true),
    FETCH_TIMEOUT(true),
    EMBEDDING_FAILED(true),
    NO_EVIDENCE(false),
    SOURCE_NOT_CERTIFIED(false),
    UNSUPPORTED_SCAN_PDF(false),
    MISSING_METADATA(false),
    PARSE_FAILED(false),
    SSRF_BLOCKED(false),
    ALLOWLIST_REJECTED(false),
    FETCH_FAILED(false),
    CONSENT_MISSING(false),
    JOB_DEADLINE_EXCEEDED(false),
    LEASE_EXPIRED(false),
    INGESTION_DISABLED(false),
    BUDGET_EXCEEDED(false),
    INTERNAL_ERROR(false);

    private final boolean transientError;

    WebSearchErrorCode(boolean transientError) {
        this.transientError = transientError;
    }

    public boolean isTransient() {
        return transientError;
    }
}
