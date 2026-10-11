package com.campusreserve.reservation.dto.resp;

import java.time.LocalDate;
import java.util.List;

/** Advisory, privacy-safe view; a booking still rechecks the room under lock. */
public record StudyRoomAvailabilityResponse(
        String resourceId,
        LocalDate date,
        List<StudyRoomWindowResponse> windows,
        List<StudyRoomOccupiedIntervalResponse> occupiedIntervals
) { }
