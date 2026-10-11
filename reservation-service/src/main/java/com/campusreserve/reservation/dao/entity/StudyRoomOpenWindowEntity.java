package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.campusreserve.reservation.domain.StudyRoomWindowStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("study_room_open_window")
public class StudyRoomOpenWindowEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long resourceId;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private StudyRoomWindowStatus status;
    private LocalDateTime createdAt;
}
