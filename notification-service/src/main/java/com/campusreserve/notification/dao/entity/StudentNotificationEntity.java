package com.campusreserve.notification.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@TableName("student_notification")
public class StudentNotificationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String notificationNo;
    private String recipientReference;
    private String sourceEventId;
    private String notificationType;
    private String title;
    private String content;
    private Instant readAt;
    private Instant createdAt;
}
