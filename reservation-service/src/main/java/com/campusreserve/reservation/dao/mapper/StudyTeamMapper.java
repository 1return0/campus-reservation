package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.StudyTeamEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StudyTeamMapper extends BaseMapper<StudyTeamEntity> {

    @Select("SELECT * FROM study_team WHERE id = #{teamId} FOR UPDATE")
    StudyTeamEntity lockById(@Param("teamId") Long teamId);

    @Update("UPDATE study_team SET status = 'CLOSED' WHERE id = #{teamId} AND status = 'FORMING'")
    int closeForming(@Param("teamId") Long teamId);

    @Update("UPDATE study_team SET status = 'BOOKED' WHERE id = #{teamId} AND status = 'FORMING'")
    int markBooked(@Param("teamId") Long teamId);

    @Update("UPDATE study_team SET status = 'FORMING' WHERE id = #{teamId} AND status = 'BOOKED'")
    int reopenAfterCancellation(@Param("teamId") Long teamId);
}
