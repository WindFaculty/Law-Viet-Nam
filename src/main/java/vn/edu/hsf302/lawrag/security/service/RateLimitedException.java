package vn.edu.hsf302.lawrag.security.service;

/** A per-subject/session/IP request budget is exhausted; maps to HTTP 429. */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
