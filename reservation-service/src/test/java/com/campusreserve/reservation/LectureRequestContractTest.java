package com.campusreserve.reservation;

import com.campusreserve.common.exception.*;
import com.campusreserve.reservation.controller.front.LectureReservationRequestController;
import com.campusreserve.reservation.dto.resp.LectureRequestResponse;
import com.campusreserve.reservation.security.*;
import com.campusreserve.reservation.service.LectureReservationRequestService;
import com.campusreserve.reservation.web.ReservationDataAccessExceptionHandler;
import com.campusreserve.security.CampusJwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Duration;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = LectureReservationRequestController.class, properties = {
        "campus-reserve.security.jwt.hmac-base64-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "debug=false", "logging.level.root=WARN"})
@Import({ReservationSecurityConfiguration.class, ReservationJwtAuthenticationFilter.class,
        GlobalExceptionHandler.class, ReservationDataAccessExceptionHandler.class})
class LectureRequestContractTest {
    @Autowired MockMvc mvc;
    @Autowired CampusJwtService jwt;
    @MockitoBean LectureReservationRequestService service;
    String token(String role) { return "Bearer " + jwt.issue("student-1", Set.of(role), Duration.ofMinutes(5)); }

    @Test void accepted_has_poll_location_and_server_owned_identity() throws Exception {
        when(service.submit(7L, "student-1", "request_123"))
                .thenReturn(new LectureRequestResponse("request-id", "PROCESSING", null, null));
        mvc.perform(post("/api/v1/slots/7/reservation-requests").header("Idempotency-Key", "request_123")
                .header("Authorization", token("STUDENT")))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/reservation-requests/request-id"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }
    @Test void db_failure_cannot_be_accepted() throws Exception {
        when(service.submit(7L, "student-1", "request_123")).thenThrow(new DataAccessResourceFailureException("private"));
        mvc.perform(post("/api/v1/slots/7/reservation-requests").header("Idempotency-Key", "request_123")
                .header("Authorization", token("STUDENT"))).andExpect(status().isInternalServerError());
    }
    @Test void staff_forbidden_and_anonymous_unauthorized() throws Exception {
        mvc.perform(post("/api/v1/slots/7/reservation-requests").header("Idempotency-Key", "request_123")
                .header("Authorization", token("SUPPORT_STAFF"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reservation-requests/some-id")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void invalid_key_rejected_before_submission() throws Exception {
        mvc.perform(post("/api/v1/slots/7/reservation-requests").header("Idempotency-Key", "bad")
                .header("Authorization", token("STUDENT"))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
