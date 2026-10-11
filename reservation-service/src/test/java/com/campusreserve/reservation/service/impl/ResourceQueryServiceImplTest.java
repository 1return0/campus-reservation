package com.campusreserve.reservation.service.impl;

import com.campusreserve.reservation.dao.entity.ResourceSlotEntity;
import com.campusreserve.reservation.dao.mapper.ReservableResourceMapper;
import com.campusreserve.reservation.dao.mapper.ResourceSlotMapper;
import com.campusreserve.reservation.domain.SlotStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceQueryServiceImplTest {

    @Test
    void public_slot_response_includes_lecture_details() {
        ResourceSlotMapper slotMapper = mock(ResourceSlotMapper.class);
        ResourceSlotEntity slot = new ResourceSlotEntity();
        slot.setId(31L);
        slot.setResourceId(21L);
        slot.setStartsAt(LocalDateTime.of(2026, 10, 10, 10, 0));
        slot.setEndsAt(LocalDateTime.of(2026, 10, 10, 11, 0));
        slot.setStatus(SlotStatus.OPEN);
        slot.setCapacity(30);
        slot.setAvailableCapacity(29);
        slot.setEventTitle("AI Lecture");
        slot.setSpeakerName("Professor Li");
        slot.setActivityDescription("Introduction to AI applications");
        when(slotMapper.selectList(any())).thenReturn(List.of(slot));
        var service = new ResourceQueryServiceImpl(mock(ReservableResourceMapper.class), slotMapper,
                Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC));

        var response = service.listOpenSlots(21L).get(0);

        assertThat(response.eventTitle()).isEqualTo("AI Lecture");
        assertThat(response.speakerName()).isEqualTo("Professor Li");
        assertThat(response.activityDescription()).isEqualTo("Introduction to AI applications");
    }
}
