package com.campusreserve.reservation.manager.cache;

import com.campusreserve.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleValueWrapper;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheRebuildPollingTest {
    private final RedissonClient client = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final Cache raw = mock(Cache.class);
    private final CacheManager delegate = mock(CacheManager.class);

    private Cache cache(long waitMillis) {
        when(client.getLock(anyString())).thenReturn(lock);
        when(raw.getName()).thenReturn("windows");
        when(delegate.getCache("windows")).thenReturn(raw);
        return new DistributedRebuildCacheManager(delegate, () -> client, waitMillis).getCache("windows");
    }

    @Test void other_instance_refill_returns_without_reacquiring_lock() throws Exception {
        Cache cache = cache(1000);
        when(raw.get(15L)).thenReturn(null, new SimpleValueWrapper("other instance"));
        assertThat(cache.<String>get(15L, () -> { throw new AssertionError("must not load"); }))
                .isEqualTo("other instance");
        verify(lock, times(1)).tryLock(0, TimeUnit.MILLISECONDS);
        verify(lock, never()).unlock();
    }

    @Test void zero_wait_still_attempts_once_then_returns_busy_without_loading() throws Exception {
        Cache cache = cache(0);
        assertThatThrownBy(() -> cache.get(15L, () -> { throw new AssertionError("must not load"); }))
                .isInstanceOf(Cache.ValueRetrievalException.class).hasCauseInstanceOf(BusinessException.class);
        verify(lock, times(1)).tryLock(0, TimeUnit.MILLISECONDS);
        verify(lock, never()).unlock();
    }

    @Test void retries_after_lock_is_released_and_loads_once_if_cache_still_missing() throws Exception {
        Cache cache = cache(1000);
        when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(false, true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        assertThat(cache.get(15L, () -> "database")).isEqualTo("database");
        verify(lock, times(2)).tryLock(0, TimeUnit.MILLISECONDS);
        verify(raw).put(15L, "database");
        verify(lock).unlock();
    }
}
