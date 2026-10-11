package com.campusreserve.reservation.manager.rate;

import com.campusreserve.common.exception.BusinessException;
import com.campusreserve.reservation.config.StudyRoomAdmissionRateLimitProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;

/** Independent, optional admission limit for free-interval study-room booking. */
public class RedisStudyRoomAdmissionGuard implements StudyRoomAdmissionGuard {
    private static final String PREFIX = "campus-reserve:study-room-admission:";

    private final StringRedisTemplate redisTemplate;
    private final long windowMillis;
    private final long perRoomDayMaxRequests;
    private final long perUserPerRoomDayMaxRequests;

    public RedisStudyRoomAdmissionGuard(StringRedisTemplate redisTemplate,
                                       StudyRoomAdmissionRateLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.windowMillis = RedisDualDimensionAdmissionLimiter.positive(
                properties.getWindowMillis(), "study-room.window-millis");
        this.perRoomDayMaxRequests = RedisDualDimensionAdmissionLimiter.positive(
                properties.getPerRoomDayMaxRequests(), "study-room.per-room-day-max-requests");
        this.perUserPerRoomDayMaxRequests = RedisDualDimensionAdmissionLimiter.positive(
                properties.getPerUserPerRoomDayMaxRequests(), "study-room.per-user-per-room-day-max-requests");
    }

    @Override
    public void requireAdmission(Long roomId, LocalDate useDate, String requesterReference) {
        if (roomId == null || useDate == null || requesterReference == null || requesterReference.isBlank()) {
            throw new IllegalArgumentException("study-room admission key is incomplete");
        }
        // Both keys use the same hash tag so one Lua script also works in Redis Cluster.
        String scope = "{room:" + roomId + ":" + useDate + "}";
        Long result;
        try {
            result = RedisDualDimensionAdmissionLimiter.evaluate(redisTemplate,
                    PREFIX + scope + ":user:" + requesterReference,
                    PREFIX + scope + ":total",
                    windowMillis, perUserPerRoomDayMaxRequests, perRoomDayMaxRequests);
        } catch (DataAccessException exception) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    "study-room-admission-unavailable", "study-room-admission-unavailable");
        }
        if (result == null || result >= 0) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS,
                    "study-room-admission-rate-limited", "study-room-admission-rate-limited");
        }
    }
}
