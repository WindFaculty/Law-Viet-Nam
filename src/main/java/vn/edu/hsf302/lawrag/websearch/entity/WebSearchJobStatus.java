package vn.edu.hsf302.lawrag.websearch.entity;

/** Durable job lifecycle. QUEUED/CLAIMED/RUNNING/RETRY_WAIT are active states. */
public enum WebSearchJobStatus {
    QUEUED,
    CLAIMED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
}
