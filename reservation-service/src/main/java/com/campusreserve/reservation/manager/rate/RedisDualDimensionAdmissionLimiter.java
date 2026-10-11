package com.campusreserve.reservation.manager.rate;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/** Atomic burst admission only; it never decides whether a booking is valid. */
final class RedisDualDimensionAdmissionLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local userCount = redis.call('INCR', KEYS[1])
            if userCount == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            if userCount > tonumber(ARGV[2]) then
              return 0
            end

            local scopeCount = redis.call('INCR', KEYS[2])
            if scopeCount == 1 then
              redis.call('PEXPIRE', KEYS[2], ARGV[1])
            end
            if scopeCount > tonumber(ARGV[3]) then
              return 0
            end
            return -1
            """, Long.class);

    private RedisDualDimensionAdmissionLimiter() { }

    static Long evaluate(StringRedisTemplate redisTemplate, String userKey, String scopeKey,
                         long windowMillis, long perUserLimit, long scopeLimit) {
        return redisTemplate.execute(SCRIPT, List.of(userKey, scopeKey),
                Long.toString(windowMillis), Long.toString(perUserLimit), Long.toString(scopeLimit));
    }

    static long positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("reservation admission " + name + " must be positive");
        }
        return value;
    }
}
