package com.campusreserve.notification.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusreserve.common.api.PageResponse;
import com.campusreserve.notification.dto.resp.NotificationResponse;
import com.campusreserve.notification.service.StudentNotificationQueryService;
import com.campusreserve.notification.dao.entity.StudentNotificationEntity;
import com.campusreserve.notification.dao.mapper.StudentNotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class StudentNotificationQueryServiceImpl implements StudentNotificationQueryService {

    private final StudentNotificationMapper notificationMapper;

    @Override
    public PageResponse<NotificationResponse> listMine(String recipientReference, long page, long size) {
        Page<StudentNotificationEntity> result = notificationMapper.selectPage(
                Page.of(page, size),
                new LambdaQueryWrapper<StudentNotificationEntity>()
                        .eq(StudentNotificationEntity::getRecipientReference, recipientReference)
                        .orderByDesc(StudentNotificationEntity::getCreatedAt)
                        .orderByDesc(StudentNotificationEntity::getId)
        );
        return new PageResponse<>(
                result.getRecords().stream().map(StudentNotificationQueryServiceImpl::toResponse).toList(),
                result.getCurrent(),
                result.getSize(),
                result.getTotal()
        );
    }

    @Override
    @Transactional
    public void markRead(Long notificationId, String recipientReference) {
        int updated = notificationMapper.markReadByRecipient(notificationId, recipientReference);
        if (updated == 1) {
            return;
        }
        StudentNotificationEntity notification = notificationMapper.selectById(notificationId);
        if (notification == null || !notification.getRecipientReference().equals(recipientReference)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "notification-not-found");
        }
        // Repeated read is idempotent rather than a state conflict.
    }

    private static NotificationResponse toResponse(StudentNotificationEntity notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getNotificationType(),
                notification.getTitle(),
                notification.getContent(),
                notification.getReadAt(),
                notification.getCreatedAt()
        );
    }
}
