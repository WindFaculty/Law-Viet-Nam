package vn.edu.hsf302.lawrag.quota.dto;

import java.time.LocalDate;

public record QuotaSnapshot(int usedCount, int reservedCount, int limitCount, LocalDate period) {

    public boolean hasAvailableSlot() {
        return usedCount + reservedCount < limitCount;
    }
}
