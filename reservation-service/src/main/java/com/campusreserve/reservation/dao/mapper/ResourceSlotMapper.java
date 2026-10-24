package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResourceSlotMapper extends BaseMapper<ResourceSlotEntity> {

    @Select("SELECT * FROM resource_slot WHERE id = #{slotId} FOR UPDATE")
    ResourceSlotEntity lockById(@Param("slotId") Long slotId);

    @Update("""
            UPDATE resource_slot
            SET status = 'CANCELLED', version = version + 1
            WHERE id = #{slotId} AND status = 'OPEN' AND starts_at > #{currentTime}
            """)
    int cancelFutureOpenSlot(
            @Param("slotId") Long slotId,
            @Param("currentTime") java.time.LocalDateTime currentTime
    );

    @Select("SELECT COUNT(*) FROM resource_slot WHERE resource_id = #{resourceId} AND status = 'OPEN'")
    long countOpenSlots(@Param("resourceId") Long resourceId);

    @Select("""
            SELECT COUNT(*)
            FROM resource_slot
            WHERE resource_id = #{resourceId}
              AND status = 'OPEN'
              AND starts_at < #{endsAt}
              AND ends_at > #{startsAt}
            """)
    long countOverlappingOpenSlots(
            @Param("resourceId") Long resourceId,
            @Param("startsAt") java.time.LocalDateTime startsAt,
            @Param("endsAt") java.time.LocalDateTime endsAt
    );

    @Update("""
            UPDATE resource_slot
            SET available_capacity = available_capacity - 1,
                version = version + 1
            WHERE id = #{slotId}
              AND status = 'OPEN'
              AND available_capacity > 0
            """)
    int tryConsumeCapacity(@Param("slotId") Long slotId);

    /** New reservations claim capacity atomically; the UPDATE lock lasts until commit. */
    @Update("""
            UPDATE resource_slot
            SET available_capacity = available_capacity - 1,
                version = version + 1
            WHERE id = #{slotId}
              AND status = 'OPEN'
              AND starts_at > #{currentTime}
              AND available_capacity > 0
            """)
    int tryConsumeFutureCapacity(@Param("slotId") Long slotId,
                                 @Param("currentTime") java.time.LocalDateTime currentTime);

    @Update("""
            UPDATE resource_slot
            SET available_capacity = available_capacity + 1,
                version = version + 1
            WHERE id = #{slotId}
              AND available_capacity < capacity
            """)
    int releaseCapacity(@Param("slotId") Long slotId);

    @Update("""
            UPDATE resource_slot
            SET status = 'CLOSED', version = version + 1
            WHERE status = 'OPEN' AND ends_at <= #{currentTime}
            """)
    int closeExpiredOpenSlots(@Param("currentTime") java.time.LocalDateTime currentTime);
}
