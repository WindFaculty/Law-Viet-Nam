package vn.edu.hsf302.lawrag.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import vn.edu.hsf302.lawrag.chat.exception.ChatAccessDeniedException;
import vn.edu.hsf302.lawrag.chat.exception.ConsentExpiredException;
import vn.edu.hsf302.lawrag.chat.exception.InvalidMessageTransitionException;
import vn.edu.hsf302.lawrag.chat.exception.MessageTransitionConflictException;
import vn.edu.hsf302.lawrag.chat.exception.WebSearchJobLimitExceededException;
import vn.edu.hsf302.lawrag.chat.exception.WebSearchNotAllowedException;
import vn.edu.hsf302.lawrag.chat.exception.WebSearchRetryNotAllowedException;
import vn.edu.hsf302.lawrag.observability.service.BudgetExceededException;
import vn.edu.hsf302.lawrag.observability.filter.RequestIdFilter;
import vn.edu.hsf302.lawrag.quota.service.QuotaExceededException;
import vn.edu.hsf302.lawrag.security.service.RateLimitedException;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public String handleException(
            Exception exception,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {

        int status = resolveStatus(exception);
        response.setStatus(status);
        if (exception instanceof RateLimitedException rateLimited) {
            response.setHeader(HttpHeaders.RETRY_AFTER,
                    Long.toString(rateLimited.getRetryAfterSeconds()));
        }
        String requestId = RequestIdFilter.currentRequestId();

        model.addAttribute("status", status);
        model.addAttribute("message", messageFor(exception, status));
        model.addAttribute("requestId", requestId);

        if (status >= 500) {
            log.error("Unhandled error requestId={} path={} status={}",
                    requestId, request.getRequestURI(), status, exception);
        } else {
            log.warn("Request rejected requestId={} path={} status={} reason={}",
                    requestId, request.getRequestURI(), status, exception.getClass().getSimpleName());
        }

        boolean htmx = "true".equalsIgnoreCase(request.getHeader("HX-Request"));
        return htmx ? "fragments/error :: error" : "error";
    }

    private int resolveStatus(Exception exception) {
        if (exception instanceof NoResourceFoundException) {
            return HttpStatus.NOT_FOUND.value();
        }
        if (exception instanceof AccessDeniedException
                || exception instanceof ChatAccessDeniedException
                || exception instanceof WebSearchNotAllowedException) {
            return HttpStatus.FORBIDDEN.value();
        }
        if (exception instanceof ResponseStatusException responseStatusException) {
            return responseStatusException.getStatusCode().value();
        }
        if (exception instanceof ErrorResponse errorResponse) {
            return errorResponse.getStatusCode().value();
        }
        if (exception instanceof ConsentExpiredException
                || exception instanceof InvalidMessageTransitionException
                || exception instanceof MessageTransitionConflictException
                || exception instanceof WebSearchRetryNotAllowedException) {
            return HttpStatus.CONFLICT.value();
        }
        if (exception instanceof QuotaExceededException
                || exception instanceof WebSearchJobLimitExceededException
                || exception instanceof RateLimitedException
                || exception instanceof BudgetExceededException) {
            return HttpStatus.TOO_MANY_REQUESTS.value();
        }
        if (exception instanceof MethodArgumentTypeMismatchException
                || exception instanceof IllegalArgumentException) {
            return HttpStatus.BAD_REQUEST.value();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private String messageFor(Exception exception, int status) {
        if (exception instanceof ChatAccessDeniedException) {
            return "Bạn không có quyền truy cập hội thoại này. Vui lòng tải lại trang chat.";
        }
        if (exception instanceof WebSearchNotAllowedException) {
            return exception.getMessage() + ". Vui lòng tải lại trang chat.";
        }
        if (exception instanceof ConsentExpiredException) {
            return "Hạn xác nhận tìm Web đã hết. Vui lòng tải lại trang chat để xem trạng thái mới.";
        }
        if (exception instanceof WebSearchRetryNotAllowedException) {
            return exception.getMessage();
        }
        if (exception instanceof InvalidMessageTransitionException
                || exception instanceof MessageTransitionConflictException) {
            return "Tin nhắn đã thay đổi bởi thao tác khác hoặc trạng thái không còn hợp lệ. "
                    + "Vui lòng tải lại trang chat.";
        }
        if (exception instanceof QuotaExceededException) {
            return "Bạn đã dùng hết 5 lượt hôm nay. Quota đặt lại lúc 00:00 giờ Việt Nam.";
        }
        if (exception instanceof WebSearchJobLimitExceededException) {
            return exception.getMessage() + ". Vui lòng thử lại sau 00:00 giờ Việt Nam.";
        }
        if (exception instanceof RateLimitedException || exception instanceof BudgetExceededException) {
            return exception.getMessage();
        }
        return switch (status) {
            case 400 -> "Yêu cầu chưa hợp lệ. Vui lòng kiểm tra và thử lại.";
            case 403 -> "Bạn không có quyền thực hiện thao tác này.";
            case 404 -> "Không tìm thấy nội dung được yêu cầu.";
            case 409 -> "Dữ liệu đã thay đổi. Vui lòng tải lại trang và thử lại.";
            case 429 -> "Bạn đã gửi quá nhiều yêu cầu. Vui lòng chờ rồi thử lại.";
            default -> "Đã xảy ra lỗi khi xử lý yêu cầu. Vui lòng thử lại sau.";
        };
    }
}
