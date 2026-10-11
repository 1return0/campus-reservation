package com.campusreserve.reservation.manager.mq.delivery;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Deletes only the uniquely named topology created by maintenance-end-to-end.ps1. */
@EnabledIfEnvironmentVariable(named = "CAMPUS_RESERVE_RABBIT_LIVE_SMOKE", matches = "true")
class MaintenanceTopologyCleanupSmoke {

    @Test
    void delete_only_the_requested_e2e_topology() throws Exception {
        String suffix = System.getenv("CAMPUS_RESERVE_E2E_SUFFIX");
        assertThat(suffix).matches("[0-9a-f]{12}");
        String base = "campus.e2e." + suffix;
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(required("RABBITMQ_HOST"));
        factory.setPort(Integer.parseInt(required("RABBITMQ_PORT")));
        factory.setVirtualHost(required("RABBITMQ_VIRTUAL_HOST"));
        factory.setUsername(required("RABBITMQ_USERNAME"));
        factory.setPassword(required("RABBITMQ_PASSWORD"));
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            deleteQueue(channel, base + ".queue");
            deleteQueue(channel, base + ".dlq");
            deleteExchange(channel, base);
            deleteExchange(channel, base + ".dlx");
        }
    }

    private static void deleteQueue(Channel channel, String name) throws IOException {
        channel.queueDelete(name);
    }

    private static void deleteExchange(Channel channel, String name) throws IOException {
        channel.exchangeDelete(name);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
