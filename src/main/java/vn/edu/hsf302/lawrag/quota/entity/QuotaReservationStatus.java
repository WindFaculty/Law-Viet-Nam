package vn.edu.hsf302.lawrag.quota.entity;

/** A reservation is held while work is in flight and settled exactly once. */
public enum QuotaReservationStatus {
    ACTIVE,
    COMMITTED,
    RELEASED,
    EXPIRED
}
