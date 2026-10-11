package com.campusreserve.common.exception;

import com.campusreserve.common.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** Shared servlet API error envelope. Authentication filters handle missing JWTs earlier. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        return failure(exception.getStatus(), exception.getCode());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleValidation(Exception ignored) {
        return failure(HttpStatus.BAD_REQUEST, "invalid-request");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(AccessDeniedException ignored) {
        return failure(HttpStatus.FORBIDDEN, "forbidden");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return failure(status, exception.getReason() == null ? status.getReasonPhrase() : exception.getReason());
    }

    private static ResponseEntity<ApiResponse<Void>> failure(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .body(new ApiResponse<>(status.value(), code, null));
    }
}
