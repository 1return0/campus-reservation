package com.campusreserve.reservation.dto.resp;

import com.campusreserve.reservation.domain.StudyRoomWindowStatus;

import java.time.LocalDateTime;

public record StudyRoomWindowResponse(String id, String resourceId, LocalDateTime startsAt,
                                      LocalDateTime endsAt, StudyRoomWindowStatus status) { }
