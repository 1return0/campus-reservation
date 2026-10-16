package com.campusreserve.reservation.service.impl;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.LectureReservationRequestEntity;
import com.campusreserve.reservation.dao.mapper.LectureReservationRequestMapper;
import com.campusreserve.reservation.manager.mq.delivery.LectureRequestPersistence;
import com.campusreserve.reservation.manager.rate.ReservationAdmissionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionSystemException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LectureReservationRequestServiceImplTest {
    final LectureReservationRequestMapper requests = mock(LectureReservationRequestMapper.class);
    final LectureRequestPersistence persistence = mock(LectureRequestPersistence.class);
    final ReservationAdmissionGuard admission = mock(ReservationAdmissionGuard.class);
    final LectureReservationRequestServiceImpl service = new LectureReservationRequestServiceImpl(requests, persistence, admission);

    @Test void missing_required_switches_cannot_accept() {
        assertThatThrownBy(() -> service.submit(7L, "owner", "request_123"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(persistence, admission);
    }
    @Test void durable_replay_works_even_when_new_admission_is_disabled() {
        var existing = new LectureReservationRequestEntity();
        existing.setSlotId(7L); existing.setStatus("PROCESSING"); existing.setRequestId("durable-id");
        when(requests.replay("owner", "request_123")).thenReturn(existing);
        assertThat(service.submit(7L, "owner", "request_123").requestId()).isEqualTo("durable-id");
        assertThatThrownBy(() -> service.submit(8L, "owner", "request_123"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(persistence, admission);
    }
    @Test void uncertain_commit_never_returns_accepted_or_an_authorization_error() {
        for (String field : new String[]{"enabled", "deliveryEnabled", "admissionEnabled"}) ReflectionTestUtils.setField(service, field, true);
        when(persistence.accept(7L, "owner", "request_123")).thenThrow(new TransactionSystemException("private driver detail"));
        assertThatThrownBy(() -> service.submit(7L, "owner", "request_123"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.getCode()).isEqualTo("lecture-request-commit-uncertain");
                });
    }
}
