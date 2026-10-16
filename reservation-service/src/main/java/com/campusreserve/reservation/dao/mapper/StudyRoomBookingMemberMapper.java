package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface StudyRoomBookingMemberMapper extends BaseMapper<StudyRoomBookingMemberEntity> {
    @Select({"<script>", "SELECT * FROM study_room_booking_member WHERE booking_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "ORDER BY booking_id, id", "</script>"})
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<StudyRoomBookingMemberEntity> forBookings(@Param("ids") List<Long> ids);

    @Update("""
            UPDATE study_room_booking_member
            SET checked_in_at = #{checkedInAt}, checked_in_by_reference = #{operator}
            WHERE booking_id = #{bookingId} AND student_number = #{studentNumber}
              AND checked_in_at IS NULL
            """)
    int markArrived(@Param("bookingId") Long bookingId,
                    @Param("studentNumber") String studentNumber,
                    @Param("operator") String operator,
                    @Param("checkedInAt") LocalDateTime checkedInAt);

    @Select("""
            SELECT COUNT(*) FROM study_room_booking_member
            WHERE booking_id = #{bookingId} AND checked_in_at IS NOT NULL
            """)
    long countArrived(@Param("bookingId") Long bookingId);
}
