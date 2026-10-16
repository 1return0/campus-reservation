package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("lecture_reservation_request")
public class LectureReservationRequestEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String requestId;
    private Long slotId;
    private Long sequenceNo;
    private String requesterReference;
    private String idempotencyKey;
    private String status;
    private Long reservationId;
    private String failureCode;
    private Integer attempts;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime nextWakeupAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    @TableField(exist = false)
    private Boolean ready;
}
