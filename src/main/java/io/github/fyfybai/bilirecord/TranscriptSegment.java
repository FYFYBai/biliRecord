package io.github.fyfybai.bilirecord;

public record TranscriptSegment(
        String segmentPath,
        long startOffsetMs,
        long endOffsetMs,
        String text,
        String language) {
}
