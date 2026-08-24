package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

public final class DanmakuEventParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DanmakuEvent parse(byte[] payload) throws IOException {
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
        return new DanmakuEvent(type, Instant.now(), root, message);
    }
}
