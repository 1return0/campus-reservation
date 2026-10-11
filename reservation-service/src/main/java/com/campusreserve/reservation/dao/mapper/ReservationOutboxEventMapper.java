package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.domain.OutboxPublishStatus;
import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReservationOutboxEventMapper extends BaseMapper<ReservationOutboxEventEntity> {

    @Select("""
            SELECT COUNT(*)
            FROM reservation_outbox_event
            WHERE publish_status = #{publishStatus}
            """)
    long countByPublishStatus(@Param("publishStatus") OutboxPublishStatus publishStatus);

    @Select("""
            SELECT MIN(created_at)
            FROM reservation_outbox_event
            WHERE publish_status = 'PENDING'
            """)
    Instant oldestPendingCreatedAt();

    @Select("""
            SELECT * FROM reservation_outbox_event
            WHERE publish_status = 'PENDING'
               OR (publish_status = 'LEASED' AND lease_expires_at < CURRENT_TIMESTAMP(3))
            ORDER BY created_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<ReservationOutboxEventEntity> findPublishableCandidates(@Param("limit") int limit);

    @Update("""
            UPDATE reservation_outbox_event
            SET publish_status = 'LEASED', lease_token = #{leaseToken},
                lease_expires_at = #{leaseExpiresAt}, publish_attempts = publish_attempts + 1,
                last_error = NULL
            WHERE id = #{eventId}
              AND (publish_status = 'PENDING'
                   OR (publish_status = 'LEASED' AND lease_expires_at < CURRENT_TIMESTAMP(3)))
            """)
    int acquireLease(
            @Param("eventId") Long eventId,
            @Param("leaseToken") String leaseToken,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt
    );

    @Update("""
            UPDATE reservation_outbox_event
            SET publish_status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP(3),
                lease_token = NULL, lease_expires_at = NULL, last_error = NULL
            WHERE id = #{eventId} AND publish_status = 'LEASED' AND lease_token = #{leaseToken}
            """)
    int markPublished(@Param("eventId") Long eventId, @Param("leaseToken") String leaseToken);

    @Update("""
            UPDATE reservation_outbox_event
            SET publish_status = 'PENDING', lease_token = NULL, lease_expires_at = NULL,
                last_error = #{error}
            WHERE id = #{eventId} AND publish_status = 'LEASED' AND lease_token = #{leaseToken}
            """)
    int releaseAfterFailure(
            @Param("eventId") Long eventId,
            @Param("leaseToken") String leaseToken,
            @Param("error") String error
    );
}
