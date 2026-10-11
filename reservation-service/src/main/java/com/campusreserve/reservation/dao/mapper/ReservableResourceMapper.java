package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.ReservableResourceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReservableResourceMapper extends BaseMapper<ReservableResourceEntity> {

    /** Serializes slot creation for one resource; MySQL has no exclusion constraint for time ranges. */
    @Select("SELECT * FROM reservable_resource WHERE id = #{resourceId} FOR UPDATE")
    ReservableResourceEntity lockById(@Param("resourceId") Long resourceId);

    @Select("""
            SELECT COUNT(*) FROM study_room_open_window
            WHERE resource_id = #{resourceId} AND status = 'OPEN' AND ends_at > CURRENT_TIMESTAMP(3)
            """)
    long countFutureStudyRoomWindows(@Param("resourceId") Long resourceId);
}
