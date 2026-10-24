package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.ReservationOrderEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import java.util.List;

@Mapper
public interface ReservationOrderMapper extends BaseMapper<ReservationOrderEntity> {

    /** Re-read after a capacity UPDATE or fallback slot lock; bypass the earlier cached miss. */
    @Select("SELECT * FROM reservation_order WHERE requester_reference = #{requester} AND idempotency_key = #{key}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    ReservationOrderEntity findReplayFresh(@Param("requester") String requester, @Param("key") String key);

    @Select("SELECT * FROM reservation_order WHERE id = #{reservationId} FOR UPDATE")
    ReservationOrderEntity lockById(@Param("reservationId") Long reservationId);

    @Select("""
            SELECT reservation.*
            FROM reservation_order reservation
            JOIN resource_slot slot ON slot.id = reservation.slot_id
            JOIN reservable_resource resource ON resource.id = slot.resource_id
            WHERE reservation.status = 'CONFIRMED'
              AND DATE_ADD(slot.starts_at, INTERVAL resource.no_show_grace_minutes MINUTE) <= #{now}
            ORDER BY slot.starts_at, reservation.id
            LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<ReservationOrderEntity> lockDueNoShows(@Param("now") java.time.LocalDateTime now,
                                                 @Param("limit") int limit);

    @Select("""
            SELECT * FROM reservation_order
            WHERE slot_id = #{slotId} AND status IN ('CONFIRMED', 'WAITLISTED')
            ORDER BY id FOR UPDATE
            """)
    List<ReservationOrderEntity> lockActiveBySlot(@Param("slotId") Long slotId);

    @Update("""
            UPDATE reservation_order
            SET status = 'CANCELLED_BY_ADMIN', active_marker = NULL,
                cancelled_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE id = #{reservationId} AND status IN ('CONFIRMED', 'WAITLISTED')
            """)
    int cancelByAdmin(@Param("reservationId") Long reservationId);

    @Update("""
            UPDATE reservation_order
            SET status = 'CONFIRMED', version = version + 1
            WHERE id = #{reservationId} AND status = 'PENDING'
            """)
    int markConfirmed(@Param("reservationId") Long reservationId);

    @Update("""
            UPDATE reservation_order
            SET status = 'WAITLISTED', version = version + 1
            WHERE id = #{reservationId} AND status = 'PENDING'
            """)
    int markWaitlisted(@Param("reservationId") Long reservationId);

    @Update("""
            UPDATE reservation_order
            SET status = 'CONFIRMED', version = version + 1
            WHERE id = #{reservationId} AND status = 'WAITLISTED'
            """)
    int promoteWaitlisted(@Param("reservationId") Long reservationId);

    @Update("""
            UPDATE reservation_order
            SET status = 'CANCELLED', active_marker = NULL,
                cancelled_at = CURRENT_TIMESTAMP(3), version = version + 1
            WHERE id = #{reservationId}
              AND requester_reference = #{requesterReference}
              AND status IN ('CONFIRMED', 'WAITLISTED')
            """)
    int cancelActiveByOwner(
            @Param("reservationId") Long reservationId,
            @Param("requesterReference") String requesterReference
    );

    @Update("""
            UPDATE reservation_order
            SET status = 'CHECKED_IN', checked_in_at = CURRENT_TIMESTAMP(3),
                handled_by_reference = #{operatorReference}, version = version + 1
            WHERE id = #{reservationId} AND status = 'CONFIRMED'
            """)
    int checkInConfirmed(
            @Param("reservationId") Long reservationId,
            @Param("operatorReference") String operatorReference
    );

    @Update("""
            UPDATE reservation_order
            SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP(3),
                active_marker = NULL, version = version + 1
            WHERE id = #{reservationId} AND status = 'CHECKED_IN'
              AND handled_by_reference = #{operatorReference}
            """)
    int completeCheckedIn(
            @Param("reservationId") Long reservationId,
            @Param("operatorReference") String operatorReference
    );

    @Update("""
            UPDATE reservation_order
            SET status = 'NO_SHOW', no_show_at = CURRENT_TIMESTAMP(3),
                handled_by_reference = #{operatorReference}, active_marker = NULL,
                version = version + 1
            WHERE id = #{reservationId} AND status = 'CONFIRMED'
            """)
    int markNoShow(
            @Param("reservationId") Long reservationId,
            @Param("operatorReference") String operatorReference
    );
}
