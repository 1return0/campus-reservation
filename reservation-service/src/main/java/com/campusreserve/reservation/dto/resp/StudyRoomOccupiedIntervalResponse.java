package com.campusreserve.reservation.dto.resp;

import java.time.LocalDateTime;

public record StudyRoomOccupiedIntervalResponse(LocalDateTime startsAt, LocalDateTime endsAt) { }
