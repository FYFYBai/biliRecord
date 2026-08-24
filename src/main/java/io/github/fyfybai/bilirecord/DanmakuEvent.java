package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record DanmakuEvent(
        String type,
        Long serverTimestamp,
        Instant receivedAt,
        long receivedMonotonicNanos,
        JsonNode raw,
        DanmakuMessage message) {
}
