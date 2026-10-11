package com.campusreserve.reservation.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campusreserve.reservation.dao.entity.StudyTeamMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import com.campusreserve.reservation.dao.entity.StudyTeamMemberEntity;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StudyTeamMemberMapper extends BaseMapper<StudyTeamMemberEntity> {

    @Update("""
            UPDATE study_team_member
            SET status = 'ACCEPTED', user_reference = #{userReference}, accepted_at = #{acceptedAt}
            WHERE id = #{memberId} AND status = 'INVITED' AND user_reference IS NULL
            """)
    int acceptInvitation(@Param("memberId") Long memberId,
                         @Param("userReference") String userReference,
                         @Param("acceptedAt") LocalDateTime acceptedAt);

    @Update("""
            UPDATE study_team_member
            SET status = 'LEFT', user_reference = NULL, accepted_at = NULL
            WHERE id = #{memberId} AND status = 'ACCEPTED' AND user_reference = #{userReference}
            """)
    int leave(@Param("memberId") Long memberId, @Param("userReference") String userReference);

    @Update("UPDATE study_team_member SET status = 'DECLINED' WHERE id = #{memberId} AND status = 'INVITED'")
    int decline(@Param("memberId") Long memberId);

    @Select("""
            SELECT * FROM study_team_member WHERE team_id = #{teamId} AND status = 'ACCEPTED'
            ORDER BY id FOR UPDATE
            """)
    List<StudyTeamMemberEntity> acceptedForUpdate(@Param("teamId") Long teamId);
}
