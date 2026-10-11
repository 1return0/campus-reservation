package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campusreserve.reservation.domain.StudyTeamMemberStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("study_team_member")
public class StudyTeamMemberEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long teamId;
    private String studentNumber;
    private String userReference;
    private StudyTeamMemberStatus status;
    private LocalDateTime acceptedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
