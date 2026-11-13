package vn.edu.hsf302.lawrag.security.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import vn.edu.hsf302.lawrag.observability.filter.RequestIdFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Security filter errors never reach {@code @ControllerAdvice}. HTMX requests
 * therefore get a small fragment instead of a full HTML error page, and expired
 * CSRF tokens on auth forms redirect cleanly back to {@code /login?expired}.
 */
@Component
public class HtmxAwareAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        if (!"true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
            if (accessDeniedException instanceof CsrfException && isAuthAction(request)) {
                response.sendRedirect(request.getContextPath() + "/login?expired");
                return;
            }
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String requestId = RequestIdFilter.currentRequestId();
        response.getWriter().write("""
                <div class="alert alert-error" role="alert" data-error="access-denied">
                  <strong>Phiên làm việc đã hết hạn hoặc yêu cầu không hợp lệ.</strong>
                  <p>Vui lòng tải lại trang rồi thử lại.</p>
                  <p class="meta">Mã yêu cầu: <code>%s</code></p>
                </div>
                """.formatted(HtmlUtils.htmlEscape(requestId == null ? "unknown" : requestId)));
    }

    private boolean isAuthAction(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return "/login".equals(path)
                || "/login/".equals(path)
                || "/register".equals(path)
                || "/register/".equals(path)
                || "/logout".equals(path);
    }
}
