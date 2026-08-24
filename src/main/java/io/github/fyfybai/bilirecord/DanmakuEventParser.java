package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

public final class DanmakuEventParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DanmakuEvent parse(byte[] payload) throws IOException {
        Instant receivedAt = Instant.now();
        long receivedNanos = System.nanoTime();
        JsonNode root = objectMapper.readTree(payload);
        String rawType = root.path("cmd").asText("UNKNOWN");
        String type = rawType.contains(":") ? rawType.substring(0, rawType.indexOf(':')) : rawType;
        DanmakuMessage message = null;
        if ("DANMU_MSG".equals(type)) {
            JsonNode info = root.path("info");
            JsonNode user = info.path(2);
            if (info.isArray() && user.isArray()) {
                message = new DanmakuMessage(
                        user.path(0).asLong(),
                        user.path(1).asText(),
                        info.path(1).asText());
            }
        }
        return new DanmakuEvent(
                type,
                extractServerTimestamp(type, root),
                receivedAt,
                receivedNanos,
                root,
                message,
                normalize(type, root, message));
    }

    private static Long extractServerTimestamp(String type, JsonNode root) {
        long timestamp = 0;
        if (type.startsWith("DANMU_MSG")) {
            timestamp = root.path("info").path(0).path(4).asLong();
        } else if (type.startsWith("SEND_GIFT")) {
            timestamp = root.path("data").path("timestamp").asLong();
        } else if (type.startsWith("SUPER_CHAT_MESSAGE") || type.startsWith("GUARD_BUY")) {
            timestamp = root.path("data").path("start_time").asLong();
        }
        if (timestamp <= 0) {
            return null;
        }
        return timestamp < 1_000_000_000_000L ? timestamp * 1_000L : timestamp;
    }

    private static NormalizedEvent normalize(String type, JsonNode root, DanmakuMessage message) {
        JsonNode data = root.path("data");
        return switch (type) {
            case "LIVE" -> event(EventKind.LIVE);
            case "PREPARING" -> event(EventKind.PREPARING);
            case "DANMU_MSG" -> message == null ? null : new NormalizedEvent(
                    EventKind.DANMAKU,
                    message.uid(),
                    message.username(),
                    message.text(),
                    null, null, null, null, null, null, null, null);
            case "ROOM_CHANGE" -> new NormalizedEvent(
                    EventKind.ROOM_CHANGE,
                    null, null, null, null, null, null, null, null, null,
                    textOrNull(data, "title"),
                    roomArea(data));
            case "SEND_GIFT" -> new NormalizedEvent(
                    EventKind.GIFT,
                    longOrNull(data, "uid"),
                    textOrNull(data, "uname"),
                    null,
                    firstText(data, "giftName", "gift_name"),
                    integerOrNull(data, "num"),
                    longOrNull(data, "total_coin"),
                    textOrNull(data, "coin_type"),
                    null, null, null, null);
            case "SUPER_CHAT_MESSAGE" -> new NormalizedEvent(
                    EventKind.SUPER_CHAT,
                    longOrNull(data, "uid"),
                    textOrNull(data.path("user_info"), "uname"),
                    textOrNull(data, "message"),
                    null, null,
                    longOrNull(data, "price"),
                    "CNY",
                    null, null, null, null);
            case "GUARD_BUY" -> new NormalizedEvent(
                    EventKind.GUARD,
                    longOrNull(data, "uid"),
                    firstText(data, "username", "uname"),
                    null,
                    textOrNull(data, "gift_name"),
                    integerOrNull(data, "num"),
                    longOrNull(data, "price"),
                    "gold",
                    integerOrNull(data, "guard_level"),
                    guardPurchaseKind(data),
                    null, null);
            default -> null;
        };
    }

    private static NormalizedEvent event(EventKind kind) {
        return new NormalizedEvent(
                kind, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static GuardPurchaseKind guardPurchaseKind(JsonNode data) {
        if (data.path("is_renew").asBoolean(false) || data.path("is_renew").asInt() == 1) {
            return GuardPurchaseKind.RENEW;
        }
        if (data.path("is_first").asBoolean(false) || data.path("is_first").asInt() == 1) {
            return GuardPurchaseKind.NEW;
        }
        return GuardPurchaseKind.UNKNOWN;
    }

    private static String roomArea(JsonNode data) {
        String parent = textOrNull(data, "parent_area_name");
        String area = textOrNull(data, "area_name");
        if (parent == null) {
            return area;
        }
        if (area == null || parent.equals(area)) {
            return parent;
        }
        return parent + " / " + area;
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = textOrNull(node, first);
        return value == null ? textOrNull(node, second) : value;
    }

    private static String textOrNull(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value.isBlank() ? null : value;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }
}
