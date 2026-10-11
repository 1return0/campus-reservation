package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campusreserve.reservation.domain.StudyRoomBookingStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("study_room_booking")
public class StudyRoomBookingEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String bookingNo;
    private Long resourceId;
    private Long teamId;
    private String leaderReference;
    private String idempotencyKey;
    private Integer requiredAttendanceCount;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private StudyRoomBookingStatus status;
    private String cancellationReason;
    private String handledByReference;
    private String checkedInStudentNumber;
    private LocalDateTime checkedInAt;
    private LocalDateTime completedAt;
    private LocalDateTime noShowAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
