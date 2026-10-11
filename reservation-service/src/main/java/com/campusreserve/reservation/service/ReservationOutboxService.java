package com.campusreserve.reservation.service;

import com.campusreserve.reservation.domain.ReservationEventType;
import com.campusreserve.reservation.dao.entity.ReservationOrderEntity;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingEntity;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingMemberEntity;

import java.util.List;

public interface ReservationOutboxService {

    void recordStatusChanged(ReservationOrderEntity reservation, ReservationEventType eventType);

    void recordStudyRoomStatusChanged(StudyRoomBookingEntity booking,
                                      List<StudyRoomBookingMemberEntity> participants,
                                      ReservationEventType eventType);
}
