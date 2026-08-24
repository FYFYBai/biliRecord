package io.github.fyfybai.bilirecord;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record SessionTimeline(
        SessionSummary summary,
        List<SessionSegment> segments,
        List<TimelineEntry> entries,
        TranscriptionStatus transcription,
        long durationMs) {

    public SessionTimeline {
        segments = List.copyOf(segments);
        entries = entries.stream()
                .sorted(Comparator.comparingLong(TimelineEntry::offsetMs))
                .toList();
    }

    public Optional<SessionSegment> segmentAt(long offsetMs) {
        return segments.stream().filter(segment -> segment.contains(offsetMs)).findFirst();
    }

    public Optional<SessionSegment> nextSegment(long offsetMs) {
        return segments.stream()
                .filter(segment -> segment.startedOffsetMs() >= offsetMs)
                .min(Comparator.comparingLong(SessionSegment::startedOffsetMs));
    }
}
