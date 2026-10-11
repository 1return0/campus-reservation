package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.StudyRoomOpenWindowEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StudyRoomOpenWindowMapper extends BaseMapper<StudyRoomOpenWindowEntity> {
    @Select("""
            SELECT COUNT(*) FROM study_room_open_window
            WHERE resource_id = #{resourceId} AND status = 'OPEN'
              AND starts_at < #{endsAt} AND ends_at > #{startsAt}
            """)
    long countOverlapping(@Param("resourceId") Long resourceId,
                          @Param("startsAt") LocalDateTime startsAt,
                          @Param("endsAt") LocalDateTime endsAt);

    @Select("""
            SELECT COUNT(*) FROM study_room_open_window
            WHERE resource_id = #{resourceId} AND id <> #{windowId} AND status = 'OPEN'
              AND starts_at < #{endsAt} AND ends_at > #{startsAt}
            """)
    long countOtherOverlapping(@Param("resourceId") Long resourceId,
                               @Param("windowId") Long windowId,
                               @Param("startsAt") LocalDateTime startsAt,
                               @Param("endsAt") LocalDateTime endsAt);

    @Update("""
            UPDATE study_room_open_window SET starts_at = #{startsAt}, ends_at = #{endsAt}
            WHERE id = #{windowId} AND status = 'OPEN'
            """)
    int replaceInterval(@Param("windowId") Long windowId,
                        @Param("startsAt") LocalDateTime startsAt,
                        @Param("endsAt") LocalDateTime endsAt);

    @Select("""
            SELECT * FROM study_room_open_window
            WHERE resource_id = #{resourceId} AND status = 'OPEN'
              AND starts_at <= #{startsAt} AND ends_at >= #{endsAt}
            ORDER BY starts_at, id LIMIT 1 FOR UPDATE
            """)
    StudyRoomOpenWindowEntity containing(@Param("resourceId") Long resourceId,
                                         @Param("startsAt") LocalDateTime startsAt,
                                         @Param("endsAt") LocalDateTime endsAt);

    @Select("SELECT * FROM study_room_open_window WHERE id = #{windowId} FOR UPDATE")
    StudyRoomOpenWindowEntity lockById(@Param("windowId") Long windowId);

    @Select("""
            SELECT * FROM study_room_open_window WHERE resource_id = #{resourceId}
              AND status = 'OPEN' AND ends_at > #{now}
            ORDER BY starts_at, id
            """)
    List<StudyRoomOpenWindowEntity> futureOpen(@Param("resourceId") Long resourceId,
                                               @Param("now") LocalDateTime now);

    @Update("UPDATE study_room_open_window SET status = 'CLOSED' WHERE id = #{id} AND status = 'OPEN'")
    int close(@Param("id") Long id);
}
