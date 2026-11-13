package vn.edu.hsf302.lawrag.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Issues the server-managed anonymous identity (UUID v4 in an HttpOnly cookie).
 * The raw value never reaches the UI; only a short display suffix is exposed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AnonymousIdentityFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "anon_id";
    public static final String ATTRIBUTE = AnonymousIdentityFilter.class.getName() + ".id";
    public static final String SHORT_ATTRIBUTE = AnonymousIdentityFilter.class.getName() + ".short";

    private static final Pattern CANONICAL_UUID_V4 = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Duration COOKIE_MAX_AGE = Duration.ofDays(90);

    private final boolean secureCookie;

    public AnonymousIdentityFilter(@Value("${app.security.secure-cookies:false}") boolean secureCookie) {
        this.secureCookie = secureCookie;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String existing = readCookie(request);
        String anonId = existing != null && CANONICAL_UUID_V4.matcher(existing).matches()
                ? existing
                : UUID.randomUUID().toString();

        if (!anonId.equals(existing)) {
            ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, anonId)
                    .httpOnly(true)
                    .secure(secureCookie)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(COOKIE_MAX_AGE)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }

        request.setAttribute(ATTRIBUTE, anonId);
        request.setAttribute(SHORT_ATTRIBUTE, anonId.substring(0, 8).toUpperCase(Locale.ROOT));
        filterChain.doFilter(request, response);
    }

    private String readCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (var cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/")
                || path.equals("/favicon.ico")
                || path.startsWith("/actuator/");
    }
}
