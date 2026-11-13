package vn.edu.hsf302.lawrag.quota.service;

/** Reservation state is inconsistent with the requested settlement. */
public class QuotaReservationException extends RuntimeException {

    public QuotaReservationException(String message) {
        super(message);
    }
}
