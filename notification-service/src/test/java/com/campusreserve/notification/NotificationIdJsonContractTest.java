package com.campusreserve.notification;

import com.campusreserve.notification.dto.resp.NotificationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationIdJsonContractTest {

    @Test
    void notification_id_is_an_exact_json_string() throws Exception {
        long id = 2_108_168_417_163_985_123L;
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(
                new NotificationResponse(id, "RESERVATION_CONFIRMED", "title", "content", null, null)));
        assertThat(json.get("id").isTextual()).isTrue();
        assertThat(json.get("id").asText()).isEqualTo(Long.toString(id));
    }
}
