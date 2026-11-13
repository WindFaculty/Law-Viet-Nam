package vn.edu.hsf302.lawrag.quota.service;

import java.time.LocalDate;

/** No quota slot remains for the subject in the current daily period. */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(LocalDate period, int limit) {
        super("Quota exhausted for " + period + " (limit " + limit + ")");
    }
}
