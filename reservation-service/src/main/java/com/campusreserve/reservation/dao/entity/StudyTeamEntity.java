package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campusreserve.reservation.domain.StudyTeamStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("study_team")
public class StudyTeamEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String leaderReference;
    private String inviteCodeHash;
    private LocalDateTime inviteCodeExpiresAt;
    private StudyTeamStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
