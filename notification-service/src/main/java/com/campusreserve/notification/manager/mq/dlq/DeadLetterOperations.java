package com.campusreserve.notification.manager.mq.dlq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot, bounded manual operations. Closing the dedicated read channel requeues every unacked delivery. */
public final class DeadLetterOperations {
    private final Connection connection;
    private final DeadLetterInspector inspector;
    private final String queue;
    private final String exchange;
    private final long confirmTimeoutMillis;

    public DeadLetterOperations(Connection connection, DeadLetterInspector inspector, String queue,
                                String exchange, long confirmTimeoutMillis) {
        this.connection = connection;
        this.inspector = inspector;
        this.queue = queue;
        this.exchange = exchange;
        if (confirmTimeoutMillis < 1 || confirmTimeoutMillis > 30000) throw new IllegalArgumentException("invalid-confirm-timeout");
        this.confirmTimeoutMillis = confirmTimeoutMillis;
    }

    public record Replay(String outcome, DeadLetterInspector.View message, int scanned) { }
    public static final class OperationFailure extends RuntimeException {
        public OperationFailure(String code) { super(code); }
    }

    public List<DeadLetterInspector.View> inspect(int limit) throws Exception {
        checkLimit(limit);
        try (Channel channel = connection.createChannel()) {
            List<DeadLetterInspector.View> result = new ArrayList<>();
            for (int i = 0; i < limit; i++) {
                GetResponse message = channel.basicGet(queue, false);
                if (message == null) break;
                result.add(inspector.inspect(message));
            }
            return List.copyOf(result);
        }
    }

    public Replay replay(String eventId, String sha256, int scanLimit, boolean confirmed) throws Exception {
        checkLimit(scanLimit);
        if ("unknown".equals(DeadLetterInspector.safeEventId(eventId)) || sha256 == null
                || !sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid-message-selector");
        try (Channel deliveries = connection.createChannel()) {
            for (int scanned = 1; scanned <= scanLimit; scanned++) {
                GetResponse original = deliveries.basicGet(queue, false);
                if (original == null) return new Replay("not-found", null, scanned - 1);
                DeadLetterInspector.View view = inspector.inspect(original);
                if (!eventId.equals(view.eventId()) || !sha256.equals(view.sha256())) continue;
                if (!view.replayable()) return new Replay("refused-invalid-message", view, scanned);
                if (!confirmed) return new Replay("dry-run", view, scanned);
                publishConfirmed(original, view.eventType());
                // Only this delivery, only after positive confirm AND no mandatory return.
                try { deliveries.basicAck(original.getEnvelope().getDeliveryTag(), false); }
                catch (Exception exception) { throw new OperationFailure("source-ack-uncertain-published-copy-exists"); }
                return new Replay("published-and-acked", view, scanned);
            }
            return new Replay("scan-limit-reached", null, scanLimit);
        }
    }

    private void publishConfirmed(GetResponse original, String eventType) {
        try (Channel publisher = connection.createChannel()) {
            AtomicBoolean returned = new AtomicBoolean();
            publisher.confirmSelect();
            publisher.addReturnListener((code, text, ex, key, properties, body) -> returned.set(true));
            // Preserve exact original bytes and AMQP properties, including eventId and death history.
            publisher.basicPublish(exchange, DeadLetterInspector.routingKey(eventType), true,
                    original.getProps(), original.getBody());
            if (!publisher.waitForConfirms(confirmTimeoutMillis)) throw new OperationFailure("publish-nack");
            if (returned.get()) throw new OperationFailure("publish-unroutable");
        } catch (OperationFailure failure) { throw failure; }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OperationFailure("publish-interrupted-uncertain");
        } catch (TimeoutException exception) { throw new OperationFailure("publish-confirm-timeout-uncertain"); }
        catch (Exception exception) { throw new OperationFailure("publish-failed-or-uncertain"); }
    }

    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit-must-be-between-1-and-100");
    }
}
