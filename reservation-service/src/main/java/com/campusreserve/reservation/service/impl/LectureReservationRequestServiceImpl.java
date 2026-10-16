package com.campusreserve.reservation.service.impl;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dao.entity.LectureReservationRequestEntity;
import com.campusreserve.reservation.dao.mapper.LectureReservationRequestMapper;
import com.campusreserve.reservation.dto.resp.LectureRequestResponse;
import com.campusreserve.reservation.manager.mq.delivery.LectureRequestPersistence;
import com.campusreserve.reservation.manager.rate.ReservationAdmissionGuard;
import com.campusreserve.reservation.service.LectureReservationRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
@RequiredArgsConstructor
public class LectureReservationRequestServiceImpl implements LectureReservationRequestService {
    private final LectureReservationRequestMapper requests;
    private final LectureRequestPersistence persistence;
    private final ReservationAdmissionGuard admission;
    @Value("${campus-reserve.lecture-async.enabled:false}") private boolean enabled;
    @Value("${campus-reserve.outbox.delivery.enabled:false}") private boolean deliveryEnabled;
    @Value("${campus-reserve.admission.rate-limit.enabled:false}") private boolean admissionEnabled;

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public LectureRequestResponse submit(Long slotId, String owner, String key) {
        var replay = requests.replay(owner, key);
        if (replay != null) return response(LectureRequestPersistence.requireSameSlot(replay, slotId));
        if (!enabled || !deliveryEnabled || !admissionEnabled)
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "lecture-async-unavailable", "lecture-async-unavailable");
        admission.requireAdmission(slotId, owner);
        try {
            return response(persistence.accept(slotId, owner, key));
        } catch (DuplicateKeyException exception) {
            // Outside the failed transaction: cross-slot same-key races see committed data.
            replay = requests.replay(owner, key);
            if (replay != null) return response(LectureRequestPersistence.requireSameSlot(replay, slotId));
            throw exception;
        } catch (TransactionException uncertainCommit) {
            // Resolve inside MVC instead of an ERROR dispatch being mistaken for an authorization failure.
            // Caller retries the SAME key: commit may have succeeded despite a lost connection.
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "lecture-request-commit-uncertain", "lecture-request-commit-uncertain");
        }
    }

    @Override
    @PreAuthorize("hasRole('STUDENT')")
    public LectureRequestResponse get(String requestId, String owner) {
        var request = requests.owned(requestId, owner);
        if (request == null) throw new BusinessException(HttpStatus.NOT_FOUND, "lecture-request-not-found", "lecture-request-not-found");
        return response(request);
    }

    private static LectureRequestResponse response(LectureReservationRequestEntity request) {
        return new LectureRequestResponse(request.getRequestId(), request.getStatus(), request.getReservationId(), request.getFailureCode());
    }
}
