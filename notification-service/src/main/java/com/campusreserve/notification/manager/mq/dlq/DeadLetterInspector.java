package com.campusreserve.notification.manager.mq.dlq;

import com.campusreserve.notification.manager.mq.event.IncomingReservationEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Whitelisted metadata only. Never returns body, recipient, arbitrary headers or exception messages. */
public final class DeadLetterInspector {
    private static final Set<String> TYPES = Set.of(
            "RESERVATION_CONFIRMED", "RESERVATION_WAITLISTED", "WAITLIST_PROMOTED",
            "RESERVATION_CANCELLED", "RESERVATION_CANCELLED_BY_ADMIN", "RESERVATION_CHECKED_IN",
            "RESERVATION_COMPLETED", "RESERVATION_NO_SHOW", "STUDY_ROOM_CONFIRMED", "STUDY_ROOM_WAITLISTED",
            "STUDY_ROOM_PROMOTED", "STUDY_ROOM_CANCELLED", "STUDY_ROOM_CANCELLED_BY_ADMIN",
            "STUDY_ROOM_CHECKED_IN", "STUDY_ROOM_COMPLETED", "STUDY_ROOM_NO_SHOW");
    private final ObjectMapper mapper;
    private final String sourceQueue;
    private final String exchange;

    public DeadLetterInspector(ObjectMapper mapper, String sourceQueue, String exchange) {
        this.mapper = mapper;
        this.sourceQueue = sourceQueue;
        this.exchange = exchange;
    }

    public record Death(String reason, long count, boolean fromConfiguredQueue) { }
    public record View(String sha256, String eventId, String eventType, boolean replayable,
                       String validationCode, boolean redelivered, List<Death> deaths,
                       String localRetryAttempts) { }

    public View inspect(GetResponse message) {
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(message.getBody())); }
        catch (Exception exception) { throw new IllegalStateException("sha256-unavailable"); }
        Map<String, Object> headers = message.getProps().getHeaders() == null ? Map.of() : message.getProps().getHeaders();
        String eventId = safeEventId(headers.get("eventId"));
        String eventType = safeType(headers.get("eventType"));
        String code = "invalid-message";
        IncomingReservationEvent event = null;
        try {
            if (message.getBody().length > 262144) throw new IllegalArgumentException();
            event = mapper.readValue(message.getBody(), IncomingReservationEvent.class);
            if (event == null || "unknown".equals(safeEventId(event.eventId()))
                    || !event.eventId().equals(eventId) || !TYPES.contains(event.eventType())
                    || !Integer.valueOf(2).equals(message.getProps().getDeliveryMode())
                    || (headers.containsKey("eventType") && !event.eventType().equals(headers.get("eventType").toString()))
                    || event.schemaVersion() != 1 || event.reservationId() == null
                    || event.reservationNo() == null || event.reservationNo().isBlank()
                    || event.requesterReference() == null || event.requesterReference().isBlank()
                    || event.reservationStatus() == null || event.reservationStatus().isBlank()
                    || event.occurredAt() == null) throw new IllegalArgumentException();
            eventType = event.eventType();
            code = "missing-or-mismatched-death-origin";
        } catch (Exception ignored) { event = null; }

        List<Death> deaths = new ArrayList<>();
        boolean correctOrigin = false;
        String expectedRoutingKey = event == null ? "" : routingKey(event.eventType());
        if (headers.get("x-death") instanceof List<?> entries) {
            for (Object item : entries.stream().limit(10).toList()) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                String reason = String.valueOf(entry.get("reason"));
                if (!Set.of("rejected", "expired", "maxlen", "delivery_limit").contains(reason)) reason = "unknown";
                long count = entry.get("count") instanceof Number n ? Math.max(0, n.longValue()) : 0;
                boolean fromQueue = sourceQueue.equals(String.valueOf(entry.get("queue")));
                deaths.add(new Death(reason, count, fromQueue));
                if (event != null && fromQueue && "rejected".equals(reason)
                        && exchange.equals(String.valueOf(entry.get("exchange")))
                        && entry.get("routing-keys") instanceof List<?> keys
                        && keys.stream().anyMatch(key -> expectedRoutingKey.equals(String.valueOf(key)))) {
                    correctOrigin = true;
                }
            }
        }
        boolean replayable = event != null && correctOrigin;
        return new View(hash, eventId, eventType, replayable, replayable ? "valid" : code,
                message.getEnvelope().isRedeliver(), List.copyOf(deaths),
                "unknown: stateless retry attempts are not persisted in x-death");
    }

    public static String safeEventId(Object value) {
        if (value == null) return "unknown";
        String text = value.toString();
        try { return UUID.fromString(text).toString().equals(text) ? text : "unknown"; }
        catch (IllegalArgumentException ignored) { return "unknown"; }
    }

    public static String safeType(Object value) {
        return value != null && TYPES.contains(value.toString()) ? value.toString() : "unknown";
    }

    public static String routingKey(String eventType) { return "reservation." + eventType.toLowerCase(Locale.ROOT); }
}
