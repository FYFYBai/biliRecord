package io.github.fyfybai.bilirecord;

import java.nio.file.Path;

public record SessionSegment(long id, Path path, long startedOffsetMs, long endedOffsetMs) {
    public boolean contains(long offsetMs) {
        return offsetMs >= startedOffsetMs && offsetMs < endedOffsetMs;
    }

    public long durationMs() {
        return Math.max(0, endedOffsetMs - startedOffsetMs);
    }
}
