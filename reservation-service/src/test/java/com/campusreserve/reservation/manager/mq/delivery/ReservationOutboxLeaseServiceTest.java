package com.campusreserve.reservation.manager.mq.delivery;

import com.campusreserve.reservation.dao.entity.ReservationOutboxEventEntity;
import com.campusreserve.reservation.dao.mapper.ReservationOutboxEventMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationOutboxLeaseServiceTest {

    @Test
    void refuses_to_mark_published_when_the_lease_token_is_no_longer_current() {
        ReservationOutboxEventMapper mapper = mock(ReservationOutboxEventMapper.class);
        ReservationOutboxLeaseService service = new ReservationOutboxLeaseService(mapper);
        when(mapper.markPublished(1001L, "stale-lease")).thenReturn(0);

        assertThatThrownBy(() -> service.markPublished(1001L, "stale-lease"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox-mark-published-conflict");
    }

    @Test
    void releases_a_failed_lease_with_a_safe_default_error_code() {
        ReservationOutboxEventMapper mapper = mock(ReservationOutboxEventMapper.class);
        ReservationOutboxLeaseService service = new ReservationOutboxLeaseService(mapper);

        service.releaseAfterFailure(1001L, "lease-1", null);

        verify(mapper).releaseAfterFailure(1001L, "lease-1", "outbox-publisher-failed");
    }

    @Test
    void stores_lease_expiry_in_the_explicit_mysql_datetime_zone() {
        ReservationOutboxEventMapper mapper = mock(ReservationOutboxEventMapper.class);
        ReservationOutboxLeaseService service = new ReservationOutboxLeaseService(mapper);
        ReservationOutboxEventEntity event = new ReservationOutboxEventEntity();
        event.setId(1001L);
        when(mapper.acquireLease(
                org.mockito.ArgumentMatchers.eq(1001L),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)
        )).thenReturn(1);

        LocalDateTime lowerBound = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(30);
        String leaseToken = service.acquireLease(event, Duration.ofSeconds(30));
        LocalDateTime upperBound = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(30);
        ArgumentCaptor<LocalDateTime> expiry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).acquireLease(
                org.mockito.ArgumentMatchers.eq(1001L),
                org.mockito.ArgumentMatchers.eq(leaseToken),
                expiry.capture()
        );

        assertThat(leaseToken).isNotBlank();
        assertThat(expiry.getValue()).isBetween(lowerBound, upperBound);
    }
}
