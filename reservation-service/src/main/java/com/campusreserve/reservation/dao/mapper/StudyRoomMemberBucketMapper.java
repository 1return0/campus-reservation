package com.campusreserve.reservation.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface StudyRoomMemberBucketMapper {
    @Insert("""
            INSERT INTO study_room_member_bucket(student_number, bucket_start, booking_id)
            VALUES (#{studentNumber}, #{bucketStart}, #{bookingId})
            """)
    int insertBucket(@Param("studentNumber") String studentNumber,
                     @Param("bucketStart") LocalDateTime bucketStart,
                     @Param("bookingId") Long bookingId);

    @Delete("DELETE FROM study_room_member_bucket WHERE booking_id = #{bookingId}")
    int deleteByBooking(@Param("bookingId") Long bookingId);

    @Select("""
            SELECT COUNT(*) FROM study_room_member_bucket
            WHERE student_number = #{studentNumber} AND bucket_start >= #{startsAt}
              AND bucket_start < #{endsAt}
            """)
    long countMemberOverlap(@Param("studentNumber") String studentNumber,
                            @Param("startsAt") LocalDateTime startsAt,
                            @Param("endsAt") LocalDateTime endsAt);
}
