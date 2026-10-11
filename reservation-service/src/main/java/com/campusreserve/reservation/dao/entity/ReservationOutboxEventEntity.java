package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campusreserve.reservation.domain.OutboxPublishStatus;
import com.campusreserve.reservation.domain.ReservationEventType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("reservation_outbox_event")
public class ReservationOutboxEventEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String eventId;
    private String aggregateType;
    private Long aggregateId;
    private ReservationEventType eventType;
    private String payload;
    private OutboxPublishStatus publishStatus;
    private Integer publishAttempts;
    private String leaseToken;
    private Instant leaseExpiresAt;
    private Instant publishedAt;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
}
