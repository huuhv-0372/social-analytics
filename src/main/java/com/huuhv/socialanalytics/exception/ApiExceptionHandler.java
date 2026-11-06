package com.huuhv.socialanalytics.exception;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.core.MethodParameter;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Lỗi của REST API ({@code controller/api}) trả {@link ProblemDetail} (RFC 9457).
 * Client chỉ nhận thông điệp chung; chi tiết kỹ thuật chỉ ghi log (OWASP A10).
 * Lỗi chuẩn của Spring MVC (body sai định dạng, sai kiểu tham số, 404/405...) do {@link ResponseEntityExceptionHandler} xử lý.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.huuhv.socialanalytics.controller.api")
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail handleBadRequest(BadRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    ProblemDetail handleDuplicate(DuplicateResourceException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    // Race condition vượt qua bước kiểm tra trùng ở Service → unique constraint của DB chặn lại
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "Request conflicts with existing data");
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    ResponseEntity<ProblemDetail> handleAuthenticationRequired(AuthenticationRequiredException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"social-analytics\"")
                .body(problem(HttpStatus.UNAUTHORIZED, ex.getMessage()));
    }

    /**
     * Ném lại để {@code ExceptionTranslationFilter} của Spring Security trả 401/403 (vd {@code @PreAuthorize} từ chối),
     * thay vì bị handler {@link Exception} bên dưới biến thành 500.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    void rethrowSecurityException(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error while handling API request", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    /** Bổ sung danh sách field lỗi (chỉ tên field + message validation, không echo giá trị gửi lên). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Ràng buộc trên tham số method (vd {@code @RequestParam @Positive Long postId}) — trả cùng định dạng
     * {@code errors[{field, message}]} như lỗi body. Lỗi validate giá trị trả về (5xx) giữ xử lý mặc định.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        if (!status.is4xxClientError()) {
            return super.handleHandlerMethodValidationException(ex, headers, status, request);
        }
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed");
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", error instanceof FieldError fieldError
                                        ? fieldError.getField()
                                        : parameterName(result.getMethodParameter()),
                                "message", String.valueOf(error.getDefaultMessage()))))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Tên client thấy: ưu tiên tên khai báo trong {@code @RequestParam}/{@code @PathVariable}/{@code @RequestHeader}. */
    private static String parameterName(MethodParameter parameter) {
        String declared = "";
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        RequestHeader requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        if (requestParam != null) {
            declared = requestParam.name();
        } else if (pathVariable != null) {
            declared = pathVariable.name();
        } else if (requestHeader != null) {
            declared = requestHeader.name();
        }
        return declared.isEmpty() ? String.valueOf(parameter.getParameterName()) : declared;
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("API request failed with status {}", statusCode.value(), ex);
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
