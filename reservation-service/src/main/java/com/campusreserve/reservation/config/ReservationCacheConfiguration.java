package com.campusreserve.reservation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
@EnableCaching
public class ReservationCacheConfiguration implements CachingConfigurer {

    @Override
    public CacheErrorHandler errorHandler() {
        // Public catalog/windows are advisory. A Redis read/write outage must
        // not turn their database-backed GET endpoints into an outage.
        return new LoggingCacheErrorHandler();
    }

    @Bean
    @ConfigurationProperties(prefix = "campus-reserve.cache.redis")
    ReservationCacheProperties reservationCacheProperties() {
        return new ReservationCacheProperties();
    }

    @Bean
    @ConditionalOnProperty(prefix = "campus-reserve.cache.redis", name = "enabled", havingValue = "true")
    CacheManager reservationRedisCacheManager(
            RedisConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            ReservationCacheProperties properties
    ) {
        // Cache values are read back as Object. Preserve concrete response types while
        // accepting type metadata only for the application's own response objects and
        // the JDK collection/time types used inside them.
        ObjectMapper cacheObjectMapper = objectMapper.copy();
        var typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.campusreserve.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .build();
        cacheObjectMapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY);
        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(cacheObjectMapper);
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(
                        "published-resources", withJitteredTtl(defaults, properties.resourceTtlSeconds, properties.ttlJitterMillis),
                        "open-slots", withJitteredTtl(defaults, properties.slotTtlSeconds, properties.ttlJitterMillis),
                        "study-room-windows", withJitteredTtl(defaults, properties.studyRoomWindowsTtlSeconds,
                                properties.ttlJitterMillis)
                ))
                .build();
    }

    private static RedisCacheConfiguration withJitteredTtl(
            RedisCacheConfiguration defaults,
            long ttlSeconds,
            long ttlJitterMillis
    ) {
        if (ttlSeconds < 1 || ttlJitterMillis < 0) {
            throw new IllegalArgumentException("Redis cache TTL configuration is invalid");
        }
        return defaults.entryTtl((key, value) -> Duration.ofSeconds(ttlSeconds)
                .plusMillis(ttlJitterMillis == 0
                        ? 0
                        : ThreadLocalRandom.current().nextLong(ttlJitterMillis + 1)));
    }

    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    CacheManager noOpReservationCacheManager() {
        // Local/test runs remain independent from Redis; enabling Redis is explicit.
        return new NoOpCacheManager();
    }

    public static class ReservationCacheProperties {
        private boolean enabled;
        private long resourceTtlSeconds = 300;
        private long slotTtlSeconds = 30;
        private long studyRoomWindowsTtlSeconds = 20;
        private long ttlJitterMillis;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getResourceTtlSeconds() { return resourceTtlSeconds; }
        public void setResourceTtlSeconds(long resourceTtlSeconds) { this.resourceTtlSeconds = resourceTtlSeconds; }
        public long getSlotTtlSeconds() { return slotTtlSeconds; }
        public void setSlotTtlSeconds(long slotTtlSeconds) { this.slotTtlSeconds = slotTtlSeconds; }
        public long getStudyRoomWindowsTtlSeconds() { return studyRoomWindowsTtlSeconds; }
        public void setStudyRoomWindowsTtlSeconds(long value) { this.studyRoomWindowsTtlSeconds = value; }
        public long getTtlJitterMillis() { return ttlJitterMillis; }
        public void setTtlJitterMillis(long ttlJitterMillis) { this.ttlJitterMillis = ttlJitterMillis; }
    }
}
