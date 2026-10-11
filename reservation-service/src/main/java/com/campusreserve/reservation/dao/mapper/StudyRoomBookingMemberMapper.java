package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.StudyRoomBookingMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface StudyRoomBookingMemberMapper extends BaseMapper<StudyRoomBookingMemberEntity> {
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
