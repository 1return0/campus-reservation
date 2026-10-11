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
@TableName("notification_inbox_event")
public class NotificationInboxEventEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String eventId;
    private String eventType;
    private String payload;
    private Instant receivedAt;
}
