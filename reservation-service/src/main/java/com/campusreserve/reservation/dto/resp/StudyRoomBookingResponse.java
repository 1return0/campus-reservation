package com.campusreserve.reservation.dto.resp;

import com.campusreserve.reservation.domain.StudyRoomBookingStatus;

import java.time.LocalDateTime;
import java.util.List;

public record StudyRoomBookingResponse(String id, String bookingNo, String resourceId,
                                       String teamId, LocalDateTime startsAt, LocalDateTime endsAt,
                                       StudyRoomBookingStatus status, List<String> studentNumbers,
                                       String leaderStudentNumber,
                                       String cancellationReason, String handledByReference,
                                       String checkedInStudentNumber, LocalDateTime checkedInAt,
                                       LocalDateTime completedAt, LocalDateTime noShowAt,
                                       List<String> checkedInStudentNumbers, long checkedInCount,
                                       int requiredAttendanceCount) { }
