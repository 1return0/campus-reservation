package com.campusreserve.reservation;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.common.exception.GlobalExceptionHandler;
import com.campusreserve.reservation.controller.front.ReservationController;
import com.campusreserve.reservation.security.ReservationJwtAuthenticationFilter;
import com.campusreserve.reservation.security.ReservationSecurityConfiguration;
import com.campusreserve.reservation.service.ReservationService;
import com.campusreserve.reservation.web.ReservationDataAccessExceptionHandler;
import com.campusreserve.security.CampusJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(value = ReservationController.class, properties = {
        "campus-reserve.security.jwt.hmac-base64-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "debug=false", "logging.level.root=WARN"
})
@Import({ReservationSecurityConfiguration.class, ReservationJwtAuthenticationFilter.class,
        GlobalExceptionHandler.class, ReservationDataAccessExceptionHandler.class})
class ReservationErrorContractTest {
    @Autowired MockMvc mvc;
    @Autowired CampusJwtService jwt;
    @MockitoBean ReservationService service;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String role) {
        return post("/api/v1/slots/7/reservations").header("Idempotency-Key", "request_123")
                .header("Authorization", "Bearer " + jwt.issue("student-1", Set.of(role), Duration.ofMinutes(5)));
    }

    @Test void database_failure_is_sanitized_500_not_forbidden() throws Exception {
        when(service.reserve(7L, "student-1", "request_123"))
                .thenThrow(new DataAccessResourceFailureException("secret SQL credential"));
        mvc.perform(request("STUDENT")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("internal-server-error"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test void ordinary_business_conflict_stays_409() throws Exception {
        when(service.reserve(7L, "student-1", "request_123"))
                .thenThrow(new BusinessException(HttpStatus.CONFLICT,"active-reservation-exists","active-reservation-exists"));
        mvc.perform(request("STUDENT")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("active-reservation-exists"));
    }

    @Test void real_role_denial_stays_403_without_calling_service() throws Exception {
        mvc.perform(request("SUPPORT_STAFF")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void missing_authentication_stays_401() throws Exception {
        mvc.perform(post("/api/v1/slots/7/reservations").header("Idempotency-Key","request_123"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
