package io.github.fyfybai.bilirecord;

import java.nio.file.Path;

public record RecordingSegment(Path path, long startedMs, long endedMs) {
}
