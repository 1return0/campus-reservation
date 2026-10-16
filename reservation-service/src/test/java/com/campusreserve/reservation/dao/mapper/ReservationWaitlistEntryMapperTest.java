package com.campusreserve.reservation.dao.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationWaitlistEntryMapperTest {

    @Test
    void oldest_waiting_query_preserves_order_without_skipping_a_locked_head() throws Exception {
        Select select = ReservationWaitlistEntryMapper.class
                .getMethod("lockOldestWaiting", Long.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("WHERE slot_id = #{slotId} AND status = 'WAITING'")
                .contains("ORDER BY created_at ASC, id ASC")
                .endsWith("LIMIT 1 FOR UPDATE")
                .doesNotContain("SKIP LOCKED", "NOWAIT");
    }
}
