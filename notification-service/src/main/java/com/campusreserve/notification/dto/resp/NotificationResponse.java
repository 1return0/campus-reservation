package com.campusreserve.notification.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.time.Instant;

public record NotificationResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String notificationType,
        String title,
        String content,
        Instant readAt,
        Instant createdAt
) {
}
