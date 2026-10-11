package com.campusreserve.reservation.manager.rate;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.config.ReservationAdmissionRateLimitProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;


/**
 * Uses one Lua invocation to limit both a student's retries and total new
 * attempts for a hot slot. It never grants a reservation or changes capacity.
 */
public class RedisReservationAdmissionGuard implements ReservationAdmissionGuard {

    private static final String KEY_PREFIX = "campus-reserve:reservation-admission:";

    private final StringRedisTemplate redisTemplate;
    private final long windowMillis;
    private final long perSlotMaxRequests;
    private final long perUserPerSlotMaxRequests;

    public RedisReservationAdmissionGuard(
            StringRedisTemplate redisTemplate,
            ReservationAdmissionRateLimitProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.windowMillis = RedisDualDimensionAdmissionLimiter.positive(properties.getWindowMillis(), "window-millis");
        this.perSlotMaxRequests = RedisDualDimensionAdmissionLimiter.positive(properties.getPerSlotMaxRequests(), "per-slot-max-requests");
        this.perUserPerSlotMaxRequests = RedisDualDimensionAdmissionLimiter.positive(
                properties.getPerUserPerSlotMaxRequests(),
                "per-user-per-slot-max-requests"
        );
    }

    @Override
    public void requireAdmission(Long slotId, String requesterReference) {
        Long result;
        try {
            result = RedisDualDimensionAdmissionLimiter.evaluate(redisTemplate,
                    userSlotKey(slotId, requesterReference), slotKey(slotId),
                    windowMillis, perUserPerSlotMaxRequests, perSlotMaxRequests);
        } catch (DataAccessException exception) {
            // When enabled, the guard is deliberately fail-closed: otherwise a
            // Redis outage would send an uncontrolled burst to MySQL.
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "reservation-admission-unavailable",
                    "reservation-admission-unavailable"
            );
        }
        if (result == null || result >= 0) {
            throw new BusinessException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "reservation-admission-rate-limited",
                    "reservation-admission-rate-limited"
            );
        }
    }

    private static String userSlotKey(Long slotId, String requesterReference) {
        return KEY_PREFIX + "{slot:" + slotId + "}:user:" + requesterReference;
    }

    private static String slotKey(Long slotId) {
        return KEY_PREFIX + "{slot:" + slotId + "}:total";
    }
}
