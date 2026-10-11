package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StudyRoomBookingMapper extends BaseMapper<StudyRoomBookingEntity> {
    /** Locking/current read is essential under MySQL REPEATABLE READ. */
    @Select("""
            SELECT id FROM study_room_booking
            WHERE resource_id = #{resourceId} AND status IN ('CONFIRMED', 'CHECKED_IN', 'COMPLETED')
              AND starts_at < #{endsAt} AND ends_at > #{startsAt}
            ORDER BY id LIMIT 1 FOR UPDATE
            """)
    Long findConfirmedOverlapForUpdate(@Param("resourceId") Long resourceId,
                                       @Param("startsAt") LocalDateTime startsAt,
                                       @Param("endsAt") LocalDateTime endsAt);

    @Select("""
            SELECT * FROM study_room_booking WHERE resource_id = #{resourceId}
              AND status = 'WAITLISTED' AND starts_at > #{now}
            ORDER BY created_at, id FOR UPDATE
            """)
    List<StudyRoomBookingEntity> waiting(@Param("resourceId") Long resourceId,
                                         @Param("now") LocalDateTime now);

    @Select("SELECT * FROM study_room_booking WHERE id = #{id} FOR UPDATE")
    StudyRoomBookingEntity lockById(@Param("id") Long id);

    @Select("""
            SELECT booking.*
            FROM study_room_booking booking
            JOIN reservable_resource room ON room.id = booking.resource_id
            WHERE booking.status = 'CONFIRMED'
              AND DATE_ADD(booking.starts_at, INTERVAL room.no_show_grace_minutes MINUTE) <= #{now}
            ORDER BY booking.starts_at, booking.id
            LIMIT #{limit} FOR UPDATE SKIP LOCKED
            """)
    List<StudyRoomBookingEntity> lockDueNoShows(@Param("now") LocalDateTime now,
                                                 @Param("limit") int limit);

    @Select("""
            SELECT * FROM study_room_booking
            WHERE resource_id = #{resourceId}
              AND status IN ('CONFIRMED', 'WAITLISTED', 'CHECKED_IN', 'COMPLETED')
              AND starts_at < #{endsAt} AND ends_at > #{startsAt}
            ORDER BY id FOR UPDATE
            """)
    List<StudyRoomBookingEntity> activeInWindowForUpdate(@Param("resourceId") Long resourceId,
                                                           @Param("startsAt") LocalDateTime startsAt,
                                                           @Param("endsAt") LocalDateTime endsAt);

    @Update("""
            UPDATE study_room_booking SET status = 'CANCELLED'
            WHERE id = #{id} AND leader_reference = #{leaderReference}
              AND status IN ('CONFIRMED', 'WAITLISTED') AND starts_at > #{now}
            """)
    int cancelByLeader(@Param("id") Long id, @Param("leaderReference") String leaderReference,
                       @Param("now") LocalDateTime now);

    @Update("""
            UPDATE study_room_booking SET status = 'CANCELLED_BY_ADMIN', cancellation_reason = #{reason}
            WHERE id = #{id} AND status IN ('CONFIRMED', 'WAITLISTED')
            """)
    int cancelByAdmin(@Param("id") Long id, @Param("reason") String reason);

    @Update("UPDATE study_room_booking SET status = 'CONFIRMED' WHERE id = #{id} AND status = 'WAITLISTED'")
    int promote(@Param("id") Long id);

    @Select("""
            SELECT DISTINCT b.* FROM study_room_booking b
            JOIN study_room_booking_member m ON m.booking_id = b.id
            WHERE m.user_reference = #{userReference}
            ORDER BY b.created_at DESC, b.id DESC LIMIT 100
            """)
    List<StudyRoomBookingEntity> listForMember(@Param("userReference") String userReference);

    @Select("""
            SELECT * FROM study_room_booking
            WHERE status IN ('CONFIRMED', 'CHECKED_IN') AND ends_at > #{now}
            ORDER BY starts_at, id LIMIT 100
            """)
    List<StudyRoomBookingEntity> workQueue(@Param("now") LocalDateTime now);

    @Update("""
            UPDATE study_room_booking SET status = 'CHECKED_IN', handled_by_reference = #{operator},
                checked_in_student_number = #{studentNumber}, checked_in_at = #{now}
            WHERE id = #{id} AND status = 'CONFIRMED'
            """)
    int checkIn(@Param("id") Long id, @Param("operator") String operator,
                @Param("studentNumber") String studentNumber, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE study_room_booking SET status = 'COMPLETED', completed_at = #{now}
            WHERE id = #{id} AND status = 'CHECKED_IN' AND handled_by_reference = #{operator}
            """)
    int complete(@Param("id") Long id, @Param("operator") String operator,
                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE study_room_booking SET status = 'NO_SHOW', handled_by_reference = #{operator},
                no_show_at = #{now}
            WHERE id = #{id} AND status = 'CONFIRMED'
            """)
    int markNoShow(@Param("id") Long id, @Param("operator") String operator,
                   @Param("now") LocalDateTime now);
}
