package vn.edu.hsf302.lawrag.security.service;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import vn.edu.hsf302.lawrag.security.config.RateLimitProperties;

/**
 * In-process multi-layer rate limits for the single MVP instance. Counters are
 * intentionally not persisted: a restart forgets recent spam windows, while
 * durable quota/ledger still prevent extra charged turns.
 */
@Service
public class RateLimitService {

    private static final Duration SUBJECT_ACTION_WINDOW = Duration.ofMinutes(1);

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> lastSubmission = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitService(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkQuestionSubmission(String subject, String ipAddress) {
        if (!properties.enabled()) {
            return;
        }
        checkMinimumInterval(subject);
        checkWindow("ip:" + ipAddress, properties.questionsPerIpPerHour(),
                Duration.ofHours(1),
                "Bạn đã gửi quá nhiều câu hỏi từ địa chỉ này trong một giờ. "
                        + "Vui lòng thử lại sau.");
    }

    public void checkConfirmOrRetry(String subject) {
        if (!properties.enabled()) {
            return;
        }
        checkWindow("action:" + subject, properties.confirmRetryPerSubjectPerMinute(),
                SUBJECT_ACTION_WINDOW,
                "Bạn thao tác quá nhanh. Vui lòng chờ vài giây rồi thử lại.");
    }

    public void checkPoll(String sessionKey) {
        if (!properties.enabled()) {
            return;
        }
        checkWindow("poll:" + sessionKey, properties.pollsPerSessionPerMinute(),
                SUBJECT_ACTION_WINDOW,
                "Trang đang yêu cầu cập nhật quá nhanh. Vui lòng tải lại trang chat.");
    }

    private void checkMinimumInterval(String subject) {
        Duration minimum = properties.questionMinInterval();
        if (minimum.isZero() || minimum.isNegative()) {
            return;
        }
        Instant now = clock.instant();
        Instant previous = lastSubmission.put(subject, now);
        if (previous == null) {
            return;
        }
        long elapsedMillis = Duration.between(previous, now).toMillis();
        if (elapsedMillis < minimum.toMillis()) {
            long remainingSeconds = Math.max(1,
                    (minimum.toMillis() - elapsedMillis + 999) / 1000);
            throw new RateLimitedException(
                    "Bạn gửi câu hỏi quá nhanh. Vui lòng chờ "
                            + Math.max(1, minimum.toSeconds()) + " giây giữa hai câu hỏi.",
                    remainingSeconds);
        }
    }

    private void checkWindow(String key, int limit, Duration window, String message) {
        Instant now = clock.instant();
        Window counter = windows.compute(key, (ignored, existing) -> {
            if (existing == null || !now.isBefore(existing.windowStart().plus(window))) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        if (counter.count().get() > limit) {
            long retryAfter = Math.max(1,
                    Duration.between(now, counter.windowStart().plus(window)).toSeconds() + 1);
            throw new RateLimitedException(message, retryAfter);
        }
        cleanupIfNeeded();
    }

    private void cleanupIfNeeded() {
        if (windows.size() < 1_000) {
            return;
        }
        Instant cutoff = clock.instant().minus(Duration.ofHours(2));
        windows.entrySet().removeIf(entry -> entry.getValue().windowStart().isBefore(cutoff));
    }

    private record Window(Instant windowStart, AtomicInteger count) {
    }
}
