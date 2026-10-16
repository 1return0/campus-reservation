package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.ReservationWaitlistEntryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReservationWaitlistEntryMapper extends BaseMapper<ReservationWaitlistEntryEntity> {

    @Select("""
            SELECT COUNT(*)
            FROM reservation_waitlist_entry preceding
            JOIN reservation_waitlist_entry current_entry
              ON current_entry.reservation_id = #{reservationId}
            WHERE preceding.slot_id = current_entry.slot_id
              AND preceding.status = 'WAITING'
              AND (preceding.created_at < current_entry.created_at
                   OR (preceding.created_at = current_entry.created_at AND preceding.id <= current_entry.id))
            """)
    int countWaitingPosition(@Param("reservationId") Long reservationId);

    /** Preserve queue order: wait for a locked head rather than skipping to a later student. */
    @Select("""
            SELECT * FROM reservation_waitlist_entry
            WHERE slot_id = #{slotId} AND status = 'WAITING'
            ORDER BY created_at ASC, id ASC
            LIMIT 1 FOR UPDATE
            """)
    ReservationWaitlistEntryEntity lockOldestWaiting(@Param("slotId") Long slotId);

    @Update("""
            UPDATE reservation_waitlist_entry
            SET status = 'PROMOTED', promoted_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{entryId} AND status = 'WAITING'
            """)
    int markPromoted(@Param("entryId") Long entryId);

    @Update("""
            UPDATE reservation_waitlist_entry
            SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3)
            WHERE reservation_id = #{reservationId} AND status = 'WAITING'
            """)
    int cancelWaitingByReservation(@Param("reservationId") Long reservationId);
}
