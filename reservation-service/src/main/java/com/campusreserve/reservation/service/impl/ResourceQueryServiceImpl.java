package com.campusreserve.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusreserve.reservation.dto.resp.ResourceResponse;
import com.campusreserve.reservation.dto.resp.SlotResponse;
import com.campusreserve.reservation.service.ResourceQueryService;
import com.campusreserve.reservation.domain.ResourceStatus;
import com.campusreserve.reservation.domain.SlotStatus;
import com.campusreserve.reservation.dao.entity.ReservableResourceEntity;
import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import com.campusreserve.reservation.dao.mapper.ReservableResourceMapper;
import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ResourceQueryServiceImpl implements ResourceQueryService {

    private final ReservableResourceMapper resourceMapper;
    private final ResourceSlotMapper slotMapper;
    private final Clock reservationClock;

    @Override
    @Cacheable(cacheNames = "published-resources", key = "'all'", sync = true)
    public List<ResourceResponse> listPublishedResources() {
        return resourceMapper.selectList(new LambdaQueryWrapper<ReservableResourceEntity>()
                        .eq(ReservableResourceEntity::getStatus, ResourceStatus.PUBLISHED)
                        .orderByAsc(ReservableResourceEntity::getResourceName)
                        .orderByAsc(ReservableResourceEntity::getId))
                .stream()
                .map(ResourceQueryServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Cacheable(cacheNames = "open-slots", key = "#resourceId", sync = true)
    public List<SlotResponse> listOpenSlots(Long resourceId) {
        return slotMapper.selectList(new LambdaQueryWrapper<ResourceSlotEntity>()
                        .eq(ResourceSlotEntity::getResourceId, resourceId)
                        .eq(ResourceSlotEntity::getStatus, SlotStatus.OPEN)
                        .gt(ResourceSlotEntity::getStartsAt, LocalDateTime.now(reservationClock))
                        .orderByAsc(ResourceSlotEntity::getStartsAt)
                        .orderByAsc(ResourceSlotEntity::getId))
                .stream()
                .map(ResourceQueryServiceImpl::toResponse)
                .toList();
    }

    private static ResourceResponse toResponse(ReservableResourceEntity resource) {
        return new ResourceResponse(resource.getId(), resource.getResourceCode(), resource.getResourceName(),
                resource.getResourceType(), resource.getNoShowGraceMinutes(), resource.getStatus(),
                resource.getMinParticipants(), resource.getMaxParticipants());
    }

    private static SlotResponse toResponse(ResourceSlotEntity slot) {
        return new SlotResponse(slot.getId(), slot.getResourceId(), slot.getStartsAt(), slot.getEndsAt(),
                slot.getCapacity(), slot.getAvailableCapacity(), slot.getStatus(),
                slot.getEventTitle(), slot.getSpeakerName(), slot.getActivityDescription());
    }
}
