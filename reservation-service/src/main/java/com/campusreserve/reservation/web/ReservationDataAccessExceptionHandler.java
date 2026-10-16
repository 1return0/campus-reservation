package com.campusreserve.reservation.web;

import com.campusreserve.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Resolve persistence failures before container ERROR dispatch; do not relax any security rule. */
@Slf4j
@RestControllerAdvice(basePackages = "com.campusreserve.reservation")
public class ReservationDataAccessExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> persistenceFailure(DataAccessException exception) {
        // No SQL, bind values, driver message or credentials in the public response/log.
        log.error("Reservation persistence failure; type={}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(500, "internal-server-error", null));
    }
}
