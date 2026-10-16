package com.campusreserve.reservation.service;

import com.campusreserve.common.api.PageResponse;
import com.campusreserve.reservation.dto.resp.CancellationResponse;
import com.campusreserve.reservation.dto.resp.ReservationResponse;
import com.campusreserve.reservation.dto.resp.ReservationWorkItemResponse;
import com.campusreserve.reservation.domain.ReservationStatus;

import java.util.List;

public interface ReservationService {

    ReservationResponse reserve(Long slotId, String requesterReference, String idempotencyKey);

    /** Trusted durable-queue worker only; admission has already completed. */
    ReservationResponse reserveAccepted(Long slotId, String requesterReference, String idempotencyKey);

    CancellationResponse cancel(Long reservationId, String requesterReference);

    ReservationResponse getById(Long reservationId, String requesterReference);

    List<ReservationResponse> listMine(String requesterReference);

    PageResponse<ReservationWorkItemResponse> listWorkQueue(ReservationStatus status, long page, long size);

    ReservationWorkItemResponse checkIn(Long reservationId, String operatorReference);

    ReservationWorkItemResponse complete(Long reservationId, String operatorReference);

    ReservationWorkItemResponse markNoShow(Long reservationId, String operatorReference);

    /** Marks a bounded batch of confirmed fixed-slot reservations whose grace period has elapsed. */
    int autoMarkNoShows(int batchSize);
}
