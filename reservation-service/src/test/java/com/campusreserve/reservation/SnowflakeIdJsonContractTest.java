package com.campusreserve.reservation;

import com.campusreserve.reservation.domain.ResourceStatus;
import com.campusreserve.reservation.domain.SlotStatus;
import com.campusreserve.reservation.dto.resp.ResourceResponse;
import com.campusreserve.reservation.dto.resp.SlotResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SnowflakeIdJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void externally_visible_ids_are_exact_json_strings() throws Exception {
        long id = 2_108_168_417_163_985_123L;
        var resource = mapper.readTree(mapper.writeValueAsString(
                new ResourceResponse(id, "ROOM_A", "Room A", "ROOM", 15, ResourceStatus.PUBLISHED)));
        var slot = mapper.readTree(mapper.writeValueAsString(
                new SlotResponse(id, id, LocalDateTime.of(2026, 10, 10, 10, 0),
                        LocalDateTime.of(2026, 10, 10, 11, 0), 1, 1, SlotStatus.OPEN,
                        null, null, null)));

        assertThat(resource.get("resourceId").isTextual()).isTrue();
        assertThat(resource.get("resourceId").asText()).isEqualTo(Long.toString(id));
        assertThat(slot.get("slotId").asText()).isEqualTo(Long.toString(id));
        assertThat(slot.get("resourceId").asText()).isEqualTo(Long.toString(id));
    }
}
