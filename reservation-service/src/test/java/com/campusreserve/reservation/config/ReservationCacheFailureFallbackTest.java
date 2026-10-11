package com.campusreserve.reservation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationCacheFailureFallbackTest {
    @Test
    void advisory_cache_read_failure_falls_back_to_the_service_method() {
        Cache cache = mock(Cache.class);
        when(cache.get(any(), org.mockito.ArgumentMatchers.<Callable<String>>any()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        CacheManager manager = mock(CacheManager.class);
        when(manager.getCache("study-room-windows")).thenReturn(cache);

        new ApplicationContextRunner()
                .withUserConfiguration(ReservationCacheConfiguration.class, Lookup.class)
                .withBean(CacheManager.class, () -> manager)
                .run(context -> {
                    Lookup lookup = context.getBean(Lookup.class);
                    assertThat(lookup.load()).isEqualTo("from database");
                    assertThat(lookup.callCount()).isEqualTo(1);
                });
    }

    @Configuration
    static class Lookup {
        private final AtomicInteger calls = new AtomicInteger();

        @Cacheable(cacheNames = "study-room-windows", key = "'one'", sync = true)
        public String load() {
            calls.incrementAndGet();
            return "from database";
        }

        public int callCount() {
            return calls.get();
        }
    }
}
