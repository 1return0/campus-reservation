package com.campusreserve.reservation.manager.rate;

import java.time.LocalDate;

/** Optional burst shedding for a room and use date; MySQL owns booking correctness. */
public interface StudyRoomAdmissionGuard {
    void requireAdmission(Long roomId, LocalDate useDate, String requesterReference);
}
