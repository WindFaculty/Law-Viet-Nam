package vn.edu.hsf302.lawrag.legaldata.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import vn.edu.hsf302.lawrag.legaldata.entity.FreshnessStatus;

class FreshnessPolicyTest {

    @Test
    void appliesThirtyDayRuleFromPhaseZero() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");

        assertThat(FreshnessPolicy.evaluate(null, now)).isEqualTo(FreshnessStatus.UNVERIFIED);
        assertThat(FreshnessPolicy.evaluate(now.minus(29, ChronoUnit.DAYS), now))
                .isEqualTo(FreshnessStatus.FRESH);
        assertThat(FreshnessPolicy.evaluate(now.minus(31, ChronoUnit.DAYS), now))
                .isEqualTo(FreshnessStatus.STALE);
    }
}
