package com.campusreserve.reservation.manager.cache;

import com.campusreserve.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Keeps Spring Cache keys/serialization/eviction, replacing sync-load with a shared lock. */
@Slf4j
public class DistributedRebuildCacheManager implements CacheManager {
    private final CacheManager delegate;
    private final Supplier<RedissonClient> client;
    private final long waitMillis;

    public DistributedRebuildCacheManager(CacheManager delegate, Supplier<RedissonClient> client, long waitMillis) {
        if (waitMillis < 0 || waitMillis > 10000) {
            throw new IllegalArgumentException("cache rebuild wait must be between 0 and 10000 ms");
        }
        this.delegate = delegate;
        this.client = client;
        this.waitMillis = waitMillis;
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = delegate.getCache(name);
        return cache == null ? null : new RebuildCache(cache);
    }

    @Override
    public Collection<String> getCacheNames() { return delegate.getCacheNames(); }

    private class RebuildCache implements Cache {
        private final Cache cache;
        RebuildCache(Cache cache) { this.cache = cache; }
        @Override public String getName() { return cache.getName(); }
        @Override public Object getNativeCache() { return cache.getNativeCache(); }
        @Override public ValueWrapper get(Object key) { return cache.get(key); }
        @Override public <T> T get(Object key, Class<T> type) { return cache.get(key, type); }
        @Override public void put(Object key, Object value) { cache.put(key, value); }
        @Override public ValueWrapper putIfAbsent(Object key, Object value) { return cache.putIfAbsent(key, value); }
        @Override public void evict(Object key) { cache.evict(key); }
        @Override public boolean evictIfPresent(Object key) { return cache.evictIfPresent(key); }
        @Override public void clear() { cache.clear(); }
        @Override public boolean invalidate() { return cache.invalidate(); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> loader) {
            RLock lock = null;
            boolean acquired = false;
            try {
                // Only Redis operations are caught here; loader failures must never cause a second load.
                try {
                    ValueWrapper hit = cache.get(key);
                    if (hit != null) { return (T) hit.get(); }
                    lock = client.get().getLock(lockName(getName(), key));
                    // No fixed lease: Redisson watchdog renews while this client owns the lock.
                    acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
                    hit = cache.get(key); // double-check, including the bounded-wait timeout path
                    if (hit != null) { return (T) hit.get(); }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ValueRetrievalException(key, loader, busy());
                } catch (RuntimeException exception) {
                    log.warn("Public cache unavailable; falling back to database for {}", getName());
                    return load(key, loader);
                }
                if (!acquired) {
                    // Do not turn ordinary lock contention into unbounded database fan-out.
                    throw new ValueRetrievalException(key, loader, busy());
                }
                T value = load(key, loader);
                try { cache.put(key, value); }
                catch (RuntimeException exception) {
                    log.warn("Public cache write failed for {}; returning loaded value", getName());
                }
                return value;
            } finally {
                if (acquired) {
                    try { if (lock.isHeldByCurrentThread()) { lock.unlock(); } }
                    catch (RuntimeException exception) {
                        log.warn("Public cache unlock failed for {}; watchdog expiry will recover", getName());
                    }
                }
            }
        }
    }

    private static <T> T load(Object key, Callable<T> loader) {
        try { return loader.call(); }
        catch (Exception exception) { throw new Cache.ValueRetrievalException(key, loader, exception); }
    }

    private static BusinessException busy() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "public-cache-rebuild-busy", "public-cache-rebuild-busy");
    }

    private static String lockName(String cacheName, Object key) {
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(key).getBytes(StandardCharsets.UTF_8)));
            return "campus-reserve:cache-rebuild:" + cacheName + ":" + digest;
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
