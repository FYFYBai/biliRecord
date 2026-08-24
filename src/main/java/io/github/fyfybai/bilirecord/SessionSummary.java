package io.github.fyfybai.bilirecord;

import java.nio.file.Path;
import java.time.Instant;

public record SessionSummary(
        long roomId,
        long uid,
        String title,
        Instant startedAt,
        Instant endedAt,
        int segments,
        long bytes,
        Path directory) {
}
