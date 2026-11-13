package vn.edu.hsf302.lawrag.legaldata.entity;

/**
 * Phase 0 freshness rule: affirmative "currently in force" answers require a
 * last verification no older than 30 days.
 */
public enum FreshnessStatus {
    FRESH,
    STALE,
    UNVERIFIED
}
