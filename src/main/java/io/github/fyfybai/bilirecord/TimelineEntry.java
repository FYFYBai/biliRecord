package io.github.fyfybai.bilirecord;

import java.util.Locale;

public record TimelineEntry(
        long offsetMs,
        long endOffsetMs,
        TimelineSource source,
        String type,
        String author,
        String text) {

    public String formattedOffset() {
        long value = Math.max(0, offsetMs);
        long totalSeconds = value / 1_000;
        return "%02d:%02d:%02d.%03d".formatted(
                totalSeconds / 3_600,
                totalSeconds / 60 % 60,
                totalSeconds % 60,
                value % 1_000);
    }

    boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        return (type + " " + author + " " + text).toLowerCase(Locale.ROOT).contains(normalized);
    }
}
