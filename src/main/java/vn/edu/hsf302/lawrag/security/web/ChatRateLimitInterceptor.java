package vn.edu.hsf302.lawrag.security.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import vn.edu.hsf302.lawrag.security.service.RateLimitService;

/**
 * Applies the locked limits only to state-changing/expensive chat routes.
 * Throwing here reaches {@code @ControllerAdvice}, which renders the HTMX error
 * fragment with 429 and Retry-After; no message/quota row is touched.
 */
@Component
public class ChatRateLimitInterceptor implements HandlerInterceptor {

    private static final Pattern SUBMIT =
            Pattern.compile("^/api/chat/sessions/[0-9a-fA-F-]{36}/messages$");
    private static final Pattern CONFIRM_OR_RETRY =
            Pattern.compile("^/api/chat/messages/[0-9a-fA-F-]{36}/(confirm-web-search|retry-web-search)$");
    private static final Pattern FRAGMENT =
            Pattern.compile("^/api/chat/messages/([0-9a-fA-F-]{36})/fragment$");

    private final RateLimitService rateLimits;
    private final JdbcTemplate jdbcTemplate;

    public ChatRateLimitInterceptor(RateLimitService rateLimits, JdbcTemplate jdbcTemplate) {
        this.rateLimits = rateLimits;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object subjectAttribute = request.getAttribute(AnonymousIdentityFilter.ATTRIBUTE);
        if (subjectAttribute == null) {
            return true;
        }
        String subject = subjectAttribute.toString();
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if ("POST".equalsIgnoreCase(request.getMethod()) && SUBMIT.matcher(path).matches()) {
            rateLimits.checkQuestionSubmission(subject, request.getRemoteAddr());
            return true;
        }
        if ("POST".equalsIgnoreCase(request.getMethod()) && CONFIRM_OR_RETRY.matcher(path).matches()) {
            rateLimits.checkConfirmOrRetry(subject);
            return true;
        }
        Matcher fragment = FRAGMENT.matcher(path);
        if ("GET".equalsIgnoreCase(request.getMethod()) && fragment.matches()) {
            rateLimits.checkPoll(sessionKey(fragment.group(1)));
        }
        return true;
    }

    private String sessionKey(String messageId) {
        try {
            List<UUID> sessions = jdbcTemplate.queryForList(
                    "select session_id from chat_messages where id = ?",
                    UUID.class, UUID.fromString(messageId));
            if (!sessions.isEmpty()) {
                return sessions.get(0).toString();
            }
        } catch (RuntimeException ignored) {
            // Unknown/malformed message: the controller will reject it; the
            // fallback key still prevents runaway polling.
        }
        return "message:" + messageId;
    }
}
