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
        String type = root.path("cmd").asText("UNKNOWN");
        DanmakuMessage message = null;
        if (type.startsWith("DANMU_MSG")) {
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
                message);
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
}
