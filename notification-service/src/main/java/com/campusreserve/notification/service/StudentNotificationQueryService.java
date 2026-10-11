package com.campusreserve.notification.service;

import com.campusreserve.notification.dto.resp.NotificationResponse;

import com.campusreserve.common.api.PageResponse;

public interface StudentNotificationQueryService {

    PageResponse<NotificationResponse> listMine(String recipientReference, long page, long size);

    void markRead(Long notificationId, String recipientReference);
}
