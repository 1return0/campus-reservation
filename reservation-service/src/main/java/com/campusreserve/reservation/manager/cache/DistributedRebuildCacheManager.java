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
import java.util.concurrent.ThreadLocalRandom;
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
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
            boolean attempted = false;
            int pauseMillis = 20;
            try {
                while (!acquired) {
                    boolean expired = attempted && System.nanoTime() >= deadline;
                    // Only Redis operations are caught here; loader failures must never cause a second load.
                    try {
                        ValueWrapper hit = cache.get(key);
                        if (hit != null) { return (T) hit.get(); }
                        if (!expired) {
                            if (lock == null) { lock = client.get().getLock(lockName(getName(), key)); }
                            // Immediate interruptible attempt, no fixed lease; watchdog renewal remains enabled.
                            acquired = lock.tryLock(0, TimeUnit.MILLISECONDS);
                            attempted = true;
                            if (acquired) {
                                hit = cache.get(key); // lock-holder double-check
                                if (hit != null) { return (T) hit.get(); }
                            }
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new ValueRetrievalException(key, loader, busy());
                    } catch (RuntimeException exception) {
                        log.warn("Public cache unavailable; falling back to database for {}", getName());
                        return load(key, loader);
                    }
                    if (expired) { throw new ValueRetrievalException(key, loader, busy()); }
                    if (!acquired) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining > 0) {
                            long delay = Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(
                                    ThreadLocalRandom.current().nextLong(pauseMillis, pauseMillis * 2L)));
                            try { TimeUnit.NANOSECONDS.sleep(delay); }
                            catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                throw new ValueRetrievalException(key, loader, busy());
                            }
                            pauseMillis = Math.min(100, pauseMillis * 2);
                        }
                    }
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
