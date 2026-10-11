package com.campusreserve.reservation.service.impl;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.dto.req.CreateSlotRequest;
import com.campusreserve.reservation.manager.cache.PublicCatalogCacheInvalidation;
import com.campusreserve.reservation.domain.ResourceStatus;
import com.campusreserve.reservation.dao.entity.ReservableResourceEntity;
import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import com.campusreserve.reservation.dao.mapper.ReservableResourceMapper;
import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import com.campusreserve.reservation.service.ResourceManagementService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceManagementServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejects_a_stale_resource_publish_update() {
        ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
        ResourceManagementService service = new ResourceManagementServiceImpl(
                resourceMapper, mock(ResourceSlotMapper.class), mock(PublicCatalogCacheInvalidation.class), CLOCK
        );
        ReservableResourceEntity resource = new ReservableResourceEntity();
        resource.setId(12L);
        resource.setStatus(ResourceStatus.DRAFT);
        resource.setVersion(0);
        when(resourceMapper.selectById(12L)).thenReturn(resource);
        when(resourceMapper.updateById(resource)).thenReturn(0);

        assertThatThrownBy(() -> service.publishResource(12L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("resource-version-conflict");
    }

    @Test
    void rejects_an_overlapping_open_slot_while_holding_the_resource_lock() {
        ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ResourceManagementService service = new ResourceManagementServiceImpl(
                resourceMapper, slotMapper, mock(PublicCatalogCacheInvalidation.class), CLOCK
        );
        ReservableResourceEntity resource = new ReservableResourceEntity();
        resource.setId(11L);
        resource.setStatus(ResourceStatus.PUBLISHED);
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 10, 10, 0);
        LocalDateTime endsAt = LocalDateTime.of(2026, 10, 10, 12, 0);
        when(resourceMapper.lockById(11L)).thenReturn(resource);
        when(slotMapper.countOverlappingOpenSlots(11L, startsAt, endsAt)).thenReturn(1L);

        assertThatThrownBy(() -> service.createOpenSlot(new CreateSlotRequest(11L, startsAt, endsAt, 10,
                null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("resource-slot-overlaps-existing");
        verify(resourceMapper).lockById(11L);
    }

    @Test
    void lecture_slot_requires_details_and_persists_them_on_the_slot() {
        ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ResourceManagementService service = new ResourceManagementServiceImpl(
                resourceMapper, slotMapper, mock(PublicCatalogCacheInvalidation.class), CLOCK
        );
        ReservableResourceEntity lecture = new ReservableResourceEntity();
        lecture.setId(21L);
        lecture.setResourceType("LECTURE");
        lecture.setStatus(ResourceStatus.PUBLISHED);
        when(resourceMapper.lockById(21L)).thenReturn(lecture);
        LocalDateTime startsAt = LocalDateTime.of(2026, 10, 10, 10, 0);
        LocalDateTime endsAt = startsAt.plusHours(1);

        assertThatThrownBy(() -> service.createOpenSlot(new CreateSlotRequest(21L, startsAt, endsAt,
                30, "AI Lecture", " ", "Introduction")))
                .isInstanceOf(BusinessException.class).hasMessage("lecture-details-required");

        var response = service.createOpenSlot(new CreateSlotRequest(21L, startsAt, endsAt,
                30, " AI Lecture ", " Professor Li ", " Introduction "));
        ArgumentCaptor<ResourceSlotEntity> captured = ArgumentCaptor.forClass(ResourceSlotEntity.class);
        verify(slotMapper).insert(captured.capture());
        assertThat(captured.getValue().getEventTitle()).isEqualTo("AI Lecture");
        assertThat(captured.getValue().getSpeakerName()).isEqualTo("Professor Li");
        assertThat(captured.getValue().getActivityDescription()).isEqualTo("Introduction");
        assertThat(response.eventTitle()).isEqualTo("AI Lecture");
    }

    @Test
    void non_lecture_slot_cannot_accept_lecture_details() {
        ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ResourceManagementService service = new ResourceManagementServiceImpl(
                resourceMapper, slotMapper, mock(PublicCatalogCacheInvalidation.class), CLOCK
        );
        ReservableResourceEntity workshop = new ReservableResourceEntity();
        workshop.setId(22L);
        workshop.setResourceType("WORKSHOP");
        workshop.setStatus(ResourceStatus.PUBLISHED);
        when(resourceMapper.lockById(22L)).thenReturn(workshop);

        assertThatThrownBy(() -> service.createOpenSlot(new CreateSlotRequest(22L,
                LocalDateTime.of(2026, 10, 10, 10, 0), LocalDateTime.of(2026, 10, 10, 11, 0),
                30, "Workshop title", null, null)))
                .isInstanceOf(BusinessException.class).hasMessage("lecture-details-only");
    }

    @Test
    void refuses_to_close_a_resource_that_still_has_open_slots() {
        ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ResourceManagementService service = new ResourceManagementServiceImpl(
                resourceMapper, slotMapper, mock(PublicCatalogCacheInvalidation.class), CLOCK
        );
        ReservableResourceEntity resource = new ReservableResourceEntity();
        resource.setId(51L);
        resource.setStatus(ResourceStatus.PUBLISHED);
        when(resourceMapper.lockById(51L)).thenReturn(resource);
        when(slotMapper.countOpenSlots(51L)).thenReturn(1L);

        assertThatThrownBy(() -> service.closeResource(51L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("resource-has-open-slots");
    }

    @Test
    void closes_a_resource_only_after_every_slot_is_no_longer_open() {
        ReservableResourceMapper resourceMapper = mock(ReservableResourceMapper.class);
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ResourceManagementService service = new ResourceManagementServiceImpl(
                resourceMapper, slotMapper, mock(PublicCatalogCacheInvalidation.class), CLOCK
        );
        ReservableResourceEntity resource = new ReservableResourceEntity();
        resource.setId(52L);
        resource.setStatus(ResourceStatus.PUBLISHED);
        resource.setVersion(0);
        when(resourceMapper.lockById(52L)).thenReturn(resource);
        when(slotMapper.countOpenSlots(52L)).thenReturn(0L);
        when(resourceMapper.updateById(resource)).thenReturn(1);

        assertThat(service.closeResource(52L).status()).isEqualTo(ResourceStatus.CLOSED);
    }
}
