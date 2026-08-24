package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Optional;

public final class DanmakuEventParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<DanmakuMessage> parse(byte[] payload) throws IOException {
        JsonNode root = objectMapper.readTree(payload);
        if (!root.path("cmd").asText().startsWith("DANMU_MSG")) {
            return Optional.empty();
        }
        JsonNode info = root.path("info");
        JsonNode user = info.path(2);
        if (!info.isArray() || !user.isArray()) {
            return Optional.empty();
        }
        return Optional.of(new DanmakuMessage(
                user.path(0).asLong(),
                user.path(1).asText(),
                info.path(1).asText()));
    }
}
