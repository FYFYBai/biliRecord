package io.github.fyfybai.bilirecord;

import java.time.Duration;

public record RecordingStart(Duration videoPosition, long sessionOffsetMs) {
}
