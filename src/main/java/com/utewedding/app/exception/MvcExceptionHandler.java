package com.utewedding.app.exception;

import jakarta.servlet.http.HttpServletRequest;
import com.utewedding.app.observability.CommerceMetrics;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.validation.BindException;
import org.springframework.web.servlet.ModelAndView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-rendered requests receive an HTML error view, never an API JSON body. */
@ControllerAdvice(basePackages = "com.utewedding.app.controller")
@Order(Ordered.LOWEST_PRECEDENCE)
public class MvcExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MvcExceptionHandler.class);
    private CommerceMetrics metrics = CommerceMetrics.noop();

    @Autowired(required = false)
    void attachMetrics(CommerceMetrics metrics) {
        this.metrics = metrics == null ? CommerceMetrics.noop() : metrics;
    }

    @ExceptionHandler({BindException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ModelAndView badRequest(Exception exception) {
        return view(HttpStatus.BAD_REQUEST, "Dữ liệu gửi lên chưa hợp lệ.");
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ModelAndView notFound(ResourceNotFoundException exception) {
        return view(HttpStatus.NOT_FOUND, "Không tìm thấy dữ liệu yêu cầu.");
    }

    @ExceptionHandler(BusinessConflictException.class)
    ModelAndView conflict(BusinessConflictException exception) {
        return view(HttpStatus.CONFLICT, "Thao tác không thể thực hiện với trạng thái hiện tại.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ModelAndView forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return view(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ModelAndView responseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        return view(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status,
                exception.getReason() == null ? "Không thể hoàn tất thao tác." : exception.getReason());
    }

    @ExceptionHandler(AuthenticationException.class)
    ModelAndView unauthorized(AuthenticationException exception) {
        return view(HttpStatus.UNAUTHORIZED, "Vui lòng đăng nhập để tiếp tục.");
    }

    @ExceptionHandler(Exception.class)
    ModelAndView unexpected(Exception exception, HttpServletRequest request) {
        metrics.critical("mvc");
        log.error("Unhandled MVC exception: {} {}",
                request.getMethod(), request.getRequestURI(), exception);
        return view(HttpStatus.INTERNAL_SERVER_ERROR, "Không thể hoàn tất thao tác. Vui lòng thử lại sau.");
    }

    private ModelAndView view(HttpStatus status, String message) {
        ModelAndView result = new ModelAndView(status == HttpStatus.FORBIDDEN ? "error/403"
                : status == HttpStatus.NOT_FOUND ? "error/404" : "error");
        result.setStatus(status);
        result.addObject("errorMessage", message);
        result.addObject("status", status.value());
        return result;
    }
}
