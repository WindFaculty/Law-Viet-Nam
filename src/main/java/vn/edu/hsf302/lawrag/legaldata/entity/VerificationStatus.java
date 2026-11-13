package vn.edu.hsf302.lawrag.legaldata.entity;

/**
 * Publication gate for documents, articles and relations. Only READY data may
 * be used for a legal answer; QUARANTINED data awaits a human reviewer.
 */
public enum VerificationStatus {
    QUARANTINED,
    READY,
    ARCHIVED,
    REJECTED
}
