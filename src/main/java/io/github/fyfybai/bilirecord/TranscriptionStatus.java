package io.github.fyfybai.bilirecord;

import java.time.Instant;

public record TranscriptionStatus(String model, String language, int segments, Instant updatedAt) {
    public static TranscriptionStatus empty() {
        return new TranscriptionStatus("", "", 0, null);
    }

    public boolean available() {
        return segments > 0;
    }
}
