package com.campusreserve.reservation.service.impl;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dto.req.CreateResourceRequest;
import com.campusreserve.reservation.dto.req.CreateSlotRequest;
import com.campusreserve.reservation.dto.resp.ResourceResponse;
import com.campusreserve.reservation.dto.resp.SlotResponse;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.service.ResourceManagementService;
import com.campusreserve.reservation.domain.ResourceStatus;
import com.campusreserve.reservation.domain.NoShowPolicy;
import com.campusreserve.reservation.domain.SlotStatus;
import com.campusreserve.reservation.dao.entity.ReservableResourceEntity;
import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import com.campusreserve.reservation.dao.mapper.ReservableResourceMapper;
import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ResourceManagementServiceImpl implements ResourceManagementService {

    private final ReservableResourceMapper resourceMapper;
    private final ResourceSlotMapper slotMapper;
    private final PublicCatalogCacheInvalidation catalogCacheInvalidation;
    private final Clock reservationClock;

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public ResourceResponse createResource(CreateResourceRequest request) {
        ReservableResourceEntity resource = new ReservableResourceEntity();
        resource.setResourceCode(request.resourceCode().trim());
        resource.setResourceName(request.resourceName().trim());
        resource.setResourceType(request.resourceType().trim());
        resource.setNoShowGraceMinutes(request.noShowGraceMinutes() == null
                ? NoShowPolicy.DEFAULT_GRACE_MINUTES
                : request.noShowGraceMinutes());
        resource.setMinParticipants(1);
        resource.setMaxParticipants(8);
        resource.setStatus(ResourceStatus.DRAFT);
        resource.setVersion(0);
        resourceMapper.insert(resource);
        catalogCacheInvalidation.evictPublishedResourcesAfterCommit();
        return toResponse(resource);
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public ResourceResponse publishResource(Long resourceId) {
        ReservableResourceEntity resource = requireResource(resourceId);
        if (resource.getStatus() == ResourceStatus.CLOSED) {
            throw conflict("closed-resource-cannot-publish");
        }
        if (resource.getStatus() == ResourceStatus.PUBLISHED) {
            return toResponse(resource);
        }
        resource.setStatus(ResourceStatus.PUBLISHED);
        if (resourceMapper.updateById(resource) != 1) {
            throw conflict("resource-version-conflict");
        }
        catalogCacheInvalidation.evictPublishedResourcesAfterCommit();
        if ("STUDY_ROOM".equals(resource.getResourceType())) {
            catalogCacheInvalidation.evictStudyRoomWindowsAfterCommit(resourceId);
        }
        return toResponse(resource);
    }

    @Override
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ResourceResponse closeResource(Long resourceId) {
        ReservableResourceEntity resource = resourceMapper.lockById(resourceId);
        if (resource == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "resource-not-found", "resource-not-found");
        }
        if (resource.getStatus() == ResourceStatus.CLOSED) {
            return toResponse(resource);
        }
        if (slotMapper.countOpenSlots(resourceId) != 0) {
            throw conflict("resource-has-open-slots");
        }
        if ("STUDY_ROOM".equals(resource.getResourceType())
                && resourceMapper.countFutureStudyRoomWindows(resourceId) != 0) {
            throw conflict("resource-has-open-study-room-windows");
        }
        resource.setStatus(ResourceStatus.CLOSED);
        if (resourceMapper.updateById(resource) != 1) {
            throw conflict("resource-version-conflict");
        }
        catalogCacheInvalidation.evictPublishedResourcesAfterCommit();
        if ("STUDY_ROOM".equals(resource.getResourceType())) {
            catalogCacheInvalidation.evictStudyRoomWindowsAfterCommit(resourceId);
        }
        return toResponse(resource);
    }

    @Override
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public List<ResourceResponse> listAllResources() {
        return resourceMapper.selectList(new LambdaQueryWrapper<ReservableResourceEntity>()
                        .orderByAsc(ReservableResourceEntity::getId))
                .stream().map(ResourceManagementServiceImpl::toResponse).toList();
    }

    @Override
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public List<SlotResponse> listSlots(Long resourceId) {
        requireResource(resourceId);
        return slotMapper.selectList(new LambdaQueryWrapper<ResourceSlotEntity>()
                        .eq(ResourceSlotEntity::getResourceId, resourceId)
                        .orderByDesc(ResourceSlotEntity::getStartsAt)
                        .orderByDesc(ResourceSlotEntity::getId))
                .stream().map(ResourceManagementServiceImpl::toResponse).toList();
    }

    @Override
    @Transactional
    @PreAuthorize("hasAnyRole('SUPPORT_STAFF', 'ADMIN')")
    public SlotResponse createOpenSlot(CreateSlotRequest request) {
        if (request.startsAt() == null || request.endsAt() == null
                || !request.startsAt().isAfter(LocalDateTime.now(reservationClock))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "invalid-slot-window", "invalid-slot-window");
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "invalid-slot-window", "invalid-slot-window");
        }
        ReservableResourceEntity resource = resourceMapper.lockById(request.resourceId());
        if (resource == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "resource-not-found", "resource-not-found");
        }
        if (resource.getStatus() != ResourceStatus.PUBLISHED) {
            throw conflict("resource-not-published");
        }
        if ("STUDY_ROOM".equals(resource.getResourceType())) {
            throw conflict("study-room-requires-interval-booking");
        }
        String eventTitle = trimToNull(request.eventTitle());
        String speakerName = trimToNull(request.speakerName());
        String activityDescription = trimToNull(request.activityDescription());
        if ("LECTURE".equals(resource.getResourceType())) {
            if (eventTitle == null || speakerName == null || activityDescription == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "lecture-details-required", "lecture-details-required");
            }
        } else if (eventTitle != null || speakerName != null || activityDescription != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "lecture-details-only", "lecture-details-only");
        }
        if (slotMapper.countOverlappingOpenSlots(resource.getId(), request.startsAt(), request.endsAt()) > 0) {
            throw conflict("resource-slot-overlaps-existing");
        }
        ResourceSlotEntity slot = new ResourceSlotEntity();
        slot.setResourceId(resource.getId());
        slot.setStartsAt(request.startsAt());
        slot.setEndsAt(request.endsAt());
        slot.setCapacity(request.capacity());
        slot.setAvailableCapacity(request.capacity());
        slot.setStatus(SlotStatus.OPEN);
        slot.setEventTitle(eventTitle);
        slot.setSpeakerName(speakerName);
        slot.setActivityDescription(activityDescription);
        slot.setVersion(0);
        slotMapper.insert(slot);
        catalogCacheInvalidation.evictOpenSlotsAfterCommit(resource.getId());
        return toResponse(slot);
    }

    private ReservableResourceEntity requireResource(Long resourceId) {
        ReservableResourceEntity resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "resource-not-found", "resource-not-found");
        }
        return resource;
    }

    private static ResourceResponse toResponse(ReservableResourceEntity resource) {
        return new ResourceResponse(
                resource.getId(),
                resource.getResourceCode(),
                resource.getResourceName(),
                resource.getResourceType(),
                resource.getNoShowGraceMinutes(),
                resource.getStatus(),
                resource.getMinParticipants(),
                resource.getMaxParticipants()
        );
    }

    private static SlotResponse toResponse(ResourceSlotEntity slot) {
        return new SlotResponse(
                slot.getId(),
                slot.getResourceId(),
                slot.getStartsAt(),
                slot.getEndsAt(),
                slot.getCapacity(),
                slot.getAvailableCapacity(),
                slot.getStatus(),
                slot.getEventTitle(),
                slot.getSpeakerName(),
                slot.getActivityDescription()
        );
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static BusinessException conflict(String code) {
        return new BusinessException(HttpStatus.CONFLICT, code, code);
    }
}
