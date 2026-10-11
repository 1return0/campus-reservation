package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campusreserve.reservation.domain.WaitlistStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("reservation_waitlist_entry")
public class ReservationWaitlistEntryEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long reservationId;
    private Long slotId;
    private String requesterReference;
    private WaitlistStatus status;
    private LocalDateTime promotedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
