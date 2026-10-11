package com.campusreserve.reservation.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter @Setter
@TableName("study_room_booking_member")
public class StudyRoomBookingMemberEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long bookingId;
    private String userReference;
    private String studentNumber;
    private LocalDateTime checkedInAt;
    private String checkedInByReference;
}
