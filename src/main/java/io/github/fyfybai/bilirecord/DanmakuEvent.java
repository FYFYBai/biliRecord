package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record DanmakuEvent(
        String type,
        Instant receivedAt,
        JsonNode raw,
        DanmakuMessage message) {
}
