package vn.edu.hsf302.lawrag.observability.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.GenericFilterBean;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import vn.edu.hsf302.lawrag.observability.config.RequestIdFilterConfig;

/**
 * Assigns one correlation ID per HTTP request, exposes it in MDC for logging and
 * mirrors it in the response. It is registered for REQUEST, ASYNC and ERROR
 * dispatches (see {@link RequestIdFilterConfig}) so the ID survives the container
 * error page. Background workers must copy this MDC value when they hand work to
 * another thread.
 */
public class RequestIdFilter extends GenericFilterBean {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern ACCEPTED_INCOMING_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String requestId = (String) request.getAttribute(MDC_KEY);
        if (requestId == null) {
            String incoming = request.getHeader(HEADER);
            requestId = incoming != null && ACCEPTED_INCOMING_ID.matcher(incoming).matches()
                    ? incoming
                    : UUID.randomUUID().toString();
            request.setAttribute(MDC_KEY, requestId);
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String currentRequestId() {
        return MDC.get(MDC_KEY);
    }
}
