package com.campusreserve.reservation.config;

import com.campusreserve.reservation.manager.cache.DistributedRebuildCacheManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Two independent JVMs and Redis clients share only Redis, not an in-process lock. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_REDIS_LIVE_SMOKE", matches = "true")
class DistributedCacheRebuildLiveSmoke {
    @Test void two_jvms_rebuild_the_same_missing_key_once() throws Exception {
        String key = "rebuild-smoke-" + UUID.randomUUID();
        RedissonClient client = connect();
        try {
            Process first = null;
            Process second = null;
            try {
                String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
                String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                String start = Long.toString(System.currentTimeMillis() + 8000);
                first = new ProcessBuilder(java, "-cp", classpath, Worker.class.getName(), key, start).inheritIO().start();
                second = new ProcessBuilder(java, "-cp", classpath, Worker.class.getName(), key, start).inheritIO().start();
                assertThat(first.waitFor(40, TimeUnit.SECONDS)).isTrue();
                assertThat(second.waitFor(40, TimeUnit.SECONDS)).isTrue();
                assertThat(first.exitValue()).isZero();
                assertThat(second.exitValue()).isZero();
                assertThat(client.getAtomicLong(key + ":loads").get()).isEqualTo(1);
                System.out.println("PASS distributed cache rebuild: jvms=2 requests=40 loader_calls=1");
            } finally {
                if (first != null && first.isAlive()) { first.destroyForcibly().waitFor(); }
                if (second != null && second.isAlive()) { second.destroyForcibly().waitFor(); }
                client.getKeys().delete(key + ":loads", "study-room-windows::" + key);
            }
        } finally { client.shutdown(); }
    }

    private static String host() { return System.getenv().getOrDefault("RESERVATION_REDIS_HOST", "127.0.0.1"); }
    private static int port() { return Integer.parseInt(System.getenv().getOrDefault("RESERVATION_REDIS_PORT", "6379")); }
    private static RedissonClient connect() {
        Config config = new Config();
        config.setThreads(2).setNettyThreads(2);
        var server = config.useSingleServer().setAddress("redis://" + host() + ":" + port())
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1).setSubscriptionConnectionPoolSize(2)
                .setConnectTimeout(1500).setTimeout(1500).setRetryAttempts(0);
        String password = System.getenv("RESERVATION_REDIS_PASSWORD");
        if (password != null && !password.isBlank()) { server.setPassword(password); }
        return Redisson.create(config);
    }

    public static class Worker {
        public static void main(String[] args) throws Exception {
            var redis = new RedisStandaloneConfiguration(host(), port());
            String password = System.getenv("RESERVATION_REDIS_PASSWORD");
            if (password != null && !password.isBlank()) { redis.setPassword(password); }
            var connection = new LettuceConnectionFactory(redis);
            connection.afterPropertiesSet();
            connection.start();
            var pool = Executors.newFixedThreadPool(20);
            RedissonClient client = connect();
            try {
                var raw = new ReservationCacheConfiguration().reservationRedisCacheManager(connection,
                        new ObjectMapper().findAndRegisterModules(), new ReservationCacheConfiguration.ReservationCacheProperties());
                var cache = new DistributedRebuildCacheManager(raw, () -> client, 5000).getCache("study-room-windows");
                var futures = new ArrayList<java.util.concurrent.Future<String>>();
                for (int i = 0; i < 20; i++) {
                    futures.add(pool.submit(() -> {
                        long wait = Long.parseLong(args[1]) - System.currentTimeMillis();
                        if (wait > 0) { Thread.sleep(wait); }
                        return cache.get(args[0], () -> {
                            client.getAtomicLong(args[0] + ":loads").incrementAndGet();
                            Thread.sleep(300);
                            return "database-result";
                        });
                    }));
                }
                for (var future : futures) { assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("database-result"); }
            } finally { pool.shutdownNow(); client.shutdown(); connection.destroy(); }
        }
    }
}
