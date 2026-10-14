package com.campusreserve.reservation.manager.cache;

import com.campusreserve.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DistributedRebuildCacheManagerTest {
    private final RedissonClient client = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final ConcurrentMapCacheManager raw = new ConcurrentMapCacheManager("windows");
    private final Cache cache = new DistributedRebuildCacheManager(raw, () -> client, 10).getCache("windows");

    private void acquired() throws InterruptedException {
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test void hit_does_not_use_lock_or_loader() {
        raw.getCache("windows").put(1L, "hit");
        assertThat(cache.<String>get(1L, () -> { throw new AssertionError(); })).isEqualTo("hit");
        verifyNoInteractions(client);
    }

    @Test void missed_value_is_loaded_cached_and_unlocked() throws Exception {
        acquired();
        assertThat(cache.get(1L, () -> "loaded")).isEqualTo("loaded");
        assertThat(raw.getCache("windows").get(1L).get()).isEqualTo("loaded");
        verify(lock).unlock();
    }

    @Test void checks_cache_again_after_acquiring_lock() throws Exception {
        acquired();
        when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenAnswer(call -> {
            raw.getCache("windows").put(1L, "other instance"); return true;
        });
        assertThat(cache.<String>get(1L, () -> { throw new AssertionError(); })).isEqualTo("other instance");
        verify(lock).unlock();
    }

    @Test void contention_timeout_does_not_fall_back_to_database() throws Exception {
        when(client.getLock(anyString())).thenReturn(lock);
        assertThatThrownBy(() -> cache.get(1L, () -> { throw new AssertionError(); }))
                .isInstanceOf(Cache.ValueRetrievalException.class).hasCauseInstanceOf(BusinessException.class);
        verify(lock, never()).unlock();
    }

    @Test void redis_lock_failure_falls_back_once() {
        when(client.getLock(anyString())).thenThrow(new RedisConnectionFailureException("offline"));
        assertThat(cache.get(1L, () -> "database")).isEqualTo("database");
    }

    @Test void cache_read_failure_falls_back_without_using_lock() {
        Cache failing = mock(Cache.class);
        when(failing.get(any())).thenThrow(new RedisConnectionFailureException("offline"));
        CacheManager manager = mock(CacheManager.class);
        when(manager.getCache("windows")).thenReturn(failing);
        Cache wrapper = new DistributedRebuildCacheManager(manager, () -> client, 10).getCache("windows");
        assertThat(wrapper.get(1L, () -> "database")).isEqualTo("database");
        verifyNoInteractions(client);
    }

    @Test void cache_write_and_unlock_failure_do_not_repeat_loader() throws Exception {
        acquired();
        Cache failing = mock(Cache.class);
        when(failing.getName()).thenReturn("windows");
        doThrow(new RedisConnectionFailureException("offline")).when(failing).put(any(), any());
        doThrow(new RedisConnectionFailureException("offline")).when(lock).unlock();
        CacheManager manager = mock(CacheManager.class);
        when(manager.getCache("windows")).thenReturn(failing);
        Callable<String> loader = mock(Callable.class);
        when(loader.call()).thenReturn("database");
        Cache wrapper = new DistributedRebuildCacheManager(manager, () -> client, 10).getCache("windows");
        assertThat(wrapper.get(1L, loader)).isEqualTo("database");
        verify(loader, times(1)).call();
    }

    @Test void loader_failure_is_propagated_without_retry_and_releases_lock() throws Exception {
        acquired();
        Callable<String> loader = mock(Callable.class);
        when(loader.call()).thenThrow(new IllegalStateException("database offline"));
        assertThatThrownBy(() -> cache.get(1L, loader)).isInstanceOf(Cache.ValueRetrievalException.class);
        verify(loader, times(1)).call();
        verify(lock).unlock();
    }

    @Test void interruption_preserves_interrupt_and_does_not_load() throws Exception {
        when(client.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        try {
            assertThatThrownBy(() -> cache.get(1L, () -> { throw new AssertionError(); }))
                    .isInstanceOf(Cache.ValueRetrievalException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void eviction_keeps_existing_cache_keys() throws Exception {
        acquired();
        cache.get(1L, () -> "loaded");
        cache.evict(1L);
        assertThat(raw.getCache("windows").get(1L)).isNull();
    }

    @Test void spring_sync_boundary_propagates_busy_instead_of_calling_loader() {
        when(client.getLock(anyString())).thenReturn(lock);
        new ApplicationContextRunner().withUserConfiguration(Lookup.class)
                .withBean(CacheManager.class, () -> new DistributedRebuildCacheManager(raw, () -> client, 10))
                .run(context -> assertThatThrownBy(() -> context.getBean(Lookup.class).load())
                        .isInstanceOf(BusinessException.class).hasMessage("public-cache-rebuild-busy"));
    }

    @Configuration
    @EnableCaching
    static class Lookup {
        @Cacheable(cacheNames = "windows", key = "'one'", sync = true)
        public String load() { throw new AssertionError("Must not load during lock contention"); }
    }
}
