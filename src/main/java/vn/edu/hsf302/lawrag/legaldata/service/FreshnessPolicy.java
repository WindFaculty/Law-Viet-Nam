package vn.edu.hsf302.lawrag.legaldata.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import vn.edu.hsf302.lawrag.legaldata.entity.FreshnessStatus;

@Component
public class FreshnessPolicy {

    public static final Duration MAX_AGE = Duration.ofDays(30);

    private final Clock clock;

    public FreshnessPolicy(Clock clock) {
        this.clock = clock;
    }

    public FreshnessStatus status(Instant lastVerifiedAt) {
        return evaluate(lastVerifiedAt, clock.instant());
    }

    public static FreshnessStatus evaluate(Instant lastVerifiedAt, Instant now) {
        if (lastVerifiedAt == null) {
            return FreshnessStatus.UNVERIFIED;
        }
        return lastVerifiedAt.plus(MAX_AGE).isBefore(now)
                ? FreshnessStatus.STALE
                : FreshnessStatus.FRESH;
    }
}
