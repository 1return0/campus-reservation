package com.campusreserve.reservation.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface StudyRoomMemberBucketMapper {
    /** A bounded bulk membership/occupancy read; bucket uniqueness still decides concurrent claims. */
    @Select({"<script>", "SELECT DISTINCT m.booking_id FROM study_room_booking_member m",
            "JOIN study_room_booking b ON b.id = m.booking_id",
            "JOIN study_room_member_bucket occupied ON occupied.student_number = m.student_number",
            "AND occupied.bucket_start &gt;= b.starts_at AND occupied.bucket_start &lt; b.ends_at",
            "WHERE m.booking_id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>", "</script>"})
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    List<Long> conflictingBookings(@Param("ids") List<Long> ids);

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
