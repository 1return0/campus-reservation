package com.campusreserve.reservation.manager.mq.event;

public record LectureReservationWakeup(int schemaVersion, Long slotId) { }
