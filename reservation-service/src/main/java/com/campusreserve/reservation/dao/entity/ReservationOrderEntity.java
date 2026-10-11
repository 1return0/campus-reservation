package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.campusreserve.reservation.domain.ReservationStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("reservation_order")
public class ReservationOrderEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String reservationNo;
    private Long slotId;
    private String requesterReference;
    private String idempotencyKey;
    private ReservationStatus status;
    private Integer activeMarker;
    private LocalDateTime cancelledAt;
    private LocalDateTime checkedInAt;
    private LocalDateTime completedAt;
    private LocalDateTime noShowAt;
    private String handledByReference;
    @Version
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
