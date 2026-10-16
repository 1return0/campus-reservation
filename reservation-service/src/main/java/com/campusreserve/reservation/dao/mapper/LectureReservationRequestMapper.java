package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.LectureReservationRequestEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface LectureReservationRequestMapper extends BaseMapper<LectureReservationRequestEntity> {
    @Insert("INSERT INTO lecture_request_sequence(slot_id,last_sequence) VALUES(#{slotId},0) ON DUPLICATE KEY UPDATE slot_id=VALUES(slot_id)")
    void lockSequence(Long slotId);

    @Update("UPDATE lecture_request_sequence SET last_sequence=last_sequence+1 WHERE slot_id=#{slotId}")
    void incrementSequence(Long slotId);

    @Select("SELECT last_sequence FROM lecture_request_sequence WHERE slot_id=#{slotId}")
    Long sequence(Long slotId);

    @Select("SELECT * FROM lecture_reservation_request WHERE requester_reference=#{owner} AND idempotency_key=#{key}")
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    LectureReservationRequestEntity replay(@Param("owner") String owner, @Param("key") String key);

    @Select("SELECT * FROM lecture_reservation_request WHERE request_id=#{requestId} AND requester_reference=#{owner}")
    LectureReservationRequestEntity owned(@Param("requestId") String requestId, @Param("owner") String owner);

    // Lock the resource_slot FIRST. Never SKIP LOCKED: a busy/delayed head cannot be bypassed.
    @Select("SELECT r.*, (r.next_attempt_at<=CURRENT_TIMESTAMP(3)) AS ready FROM lecture_reservation_request r WHERE slot_id=#{slotId} AND status='PROCESSING' ORDER BY sequence_no LIMIT 1 FOR UPDATE")
    LectureReservationRequestEntity head(Long slotId);

    @Update("UPDATE lecture_reservation_request SET status=#{status},reservation_id=#{reservationId},failure_code=#{code},completed_at=CURRENT_TIMESTAMP(3) WHERE id=#{id} AND status='PROCESSING'")
    int finish(@Param("id") Long id, @Param("status") String status, @Param("reservationId") Long reservationId, @Param("code") String code);

    @Update("UPDATE lecture_reservation_request SET attempts=attempts+1,failure_code='temporary-database-or-worker-error',next_attempt_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP(3)) WHERE id=#{id} AND status='PROCESSING'")
    int defer(@Param("id") Long id, @Param("delay") int delay);

    @Select("SELECT DISTINCT slot_id FROM lecture_reservation_request WHERE status='PROCESSING' AND next_wakeup_at<=CURRENT_TIMESTAMP(3) ORDER BY slot_id LIMIT 25")
    List<Long> recoverableSlots();

    @Update("UPDATE lecture_reservation_request SET next_wakeup_at=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)) WHERE slot_id=#{slotId} AND status='PROCESSING' AND next_wakeup_at<=CURRENT_TIMESTAMP(3)")
    int claimWakeup(Long slotId);
}
