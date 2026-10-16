package com.campusreserve.reservation.service;

import com.campusreserve.reservation.dto.resp.LectureRequestResponse;

public interface LectureReservationRequestService {
    LectureRequestResponse submit(Long slotId, String owner, String idempotencyKey);
    LectureRequestResponse get(String requestId, String owner);
}
