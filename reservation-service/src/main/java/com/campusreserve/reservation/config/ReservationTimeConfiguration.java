package com.campusreserve.reservation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** A named clock keeps time-dependent reservation rules deterministic in tests. */
@Configuration
public class ReservationTimeConfiguration {

    @Bean
    Clock reservationClock(@Value("${campus-reserve.time-zone:Asia/Shanghai}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
