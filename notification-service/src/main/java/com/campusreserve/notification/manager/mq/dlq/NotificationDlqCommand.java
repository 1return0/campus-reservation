package com.campusreserve.notification.manager.mq.dlq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;

import java.util.HashMap;
import java.util.Map;

/** Local administrator CLI, not an HTTP endpoint. No Spring Boot context, DB or business listeners start. */
public final class NotificationDlqCommand {
    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    static int run(String[] args) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try {
            String action = args.length == 0 ? "status" : args[0];
            if (!java.util.Set.of("status", "inspect", "replay").contains(action)) throw new IllegalArgumentException();
            Map<String, String> options = new HashMap<>();
            boolean confirmed = false;
            for (int i = 1; i < args.length; i++) {
                if ("--confirm-replay".equals(args[i])) {
                    if (confirmed) throw new IllegalArgumentException();
                    confirmed = true;
                } else if (java.util.Set.of("--event-id", "--sha256", "--limit").contains(args[i]) && i + 1 < args.length) {
                    if (options.put(args[i], args[++i]) != null) throw new IllegalArgumentException();
                } else { throw new IllegalArgumentException(); }
            }
            if (!"replay".equals(action) && (confirmed || options.containsKey("--event-id") || options.containsKey("--sha256"))) {
                throw new IllegalArgumentException();
            }
            int limit = Integer.parseInt(options.getOrDefault("--limit", "10"));
            if (limit < 1 || limit > 100) throw new IllegalArgumentException();
            if ("replay".equals(action) && (!options.containsKey("--event-id") || !options.containsKey("--sha256"))) {
                throw new IllegalArgumentException();
            }
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(env("RABBITMQ_HOST", "127.0.0.1"));
            factory.setPort(Integer.parseInt(env("RABBITMQ_PORT", "5672")));
            factory.setVirtualHost(env("RABBITMQ_VIRTUAL_HOST", "/"));
            factory.setUsername(required("RABBITMQ_USERNAME"));
            factory.setPassword(required("RABBITMQ_PASSWORD"));
            factory.setAutomaticRecoveryEnabled(false); // One-shot operation; never silently retry a publish.
            factory.setConnectionTimeout(5000);
            factory.setHandshakeTimeout(5000);
            factory.setRequestedHeartbeat(15);
            factory.setChannelRpcTimeout(5000);
            factory.setMaxInboundMessageBodySize(262144);
            String queue = env("NOTIFICATION_DLQ", "campus.notification.reservation.dlq");
            String source = env("NOTIFICATION_SOURCE_QUEUE", "campus.notification.reservation.v1");
            String exchange = env("RESERVATION_EVENT_EXCHANGE", "campus.reservation.events");
            try (var connection = factory.newConnection("campus-notification-dlq-ops")) {
                if ("status".equals(action)) {
                    try (var channel = connection.createChannel()) {
                        var status = channel.queueDeclarePassive(queue);
                        long threshold = Long.parseLong(env("NOTIFICATION_DLQ_ALERT_THRESHOLD", "1"));
                        if (threshold < 1) throw new IllegalArgumentException();
                        boolean alert = status.getMessageCount() >= threshold;
                        System.out.println(mapper.writeValueAsString(new DeadLetterQueueMonitor.Status(
                                status.getMessageCount(), status.getConsumerCount(), alert,
                                "ready-only; see RabbitMQ Management for unacknowledged/total")));
                        return alert ? 3 : 0;
                    }
                }
                var operations = new DeadLetterOperations(connection, new DeadLetterInspector(mapper, source, exchange),
                        queue, exchange, Long.parseLong(env("NOTIFICATION_DLQ_CONFIRM_TIMEOUT_MILLIS", "5000")));
                if ("inspect".equals(action)) {
                    System.out.println(mapper.writeValueAsString(operations.inspect(limit)));
                    return 0;
                }
                var result = operations.replay(options.get("--event-id"), options.get("--sha256"), limit, confirmed);
                System.out.println(mapper.writeValueAsString(result));
                return java.util.Set.of("dry-run", "published-and-acked").contains(result.outcome()) ? 0 : 4;
            }
        } catch (IllegalArgumentException exception) {
            System.err.println("dlq-invalid-arguments-or-configuration"); return 2;
        } catch (DeadLetterOperations.OperationFailure exception) {
            System.err.println(exception.getMessage()); return 5;
        } catch (Exception ignored) {
            System.err.println("dlq-operation-unavailable-or-uncertain; no destructive retry performed"); return 5;
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
