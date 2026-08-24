package io.github.fyfybai.bilirecord;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

public final class SessionClock {
    private final Instant sessionStartedAt;
    private final long sessionStartedNanos;
    private volatile VideoAnchor videoAnchor;

    private SessionClock(Instant sessionStartedAt, long sessionStartedNanos) {
        this.sessionStartedAt = sessionStartedAt;
        this.sessionStartedNanos = sessionStartedNanos;
    }

    public static SessionClock start() {
        return new SessionClock(Instant.now(), System.nanoTime());
    }

    static SessionClock start(Instant sessionStartedAt, long sessionStartedNanos) {
        return new SessionClock(sessionStartedAt, sessionStartedNanos);
    }

    public synchronized void anchorVideo(Duration videoPosition) {
        anchorVideo(videoPosition, Instant.now(), System.nanoTime());
    }

    synchronized void anchorVideo(Duration videoPosition, Instant observedAt, long observedNanos) {
        if (videoAnchor != null) {
            return;
        }
        long positionNanos = videoPosition.toNanos();
        videoAnchor = new VideoAnchor(
                observedAt.minusNanos(positionNanos),
                observedNanos - positionNanos);
    }

    public void anchorAtSessionStart() {
        synchronized (this) {
            if (videoAnchor == null) {
                videoAnchor = new VideoAnchor(sessionStartedAt, sessionStartedNanos);
            }
        }
    }

    public Instant sessionStartedAt() {
        return sessionStartedAt;
    }

    public Optional<Instant> videoStartedAt() {
        VideoAnchor anchor = videoAnchor;
        return anchor == null ? Optional.empty() : Optional.of(anchor.startedAt());
    }

    public OptionalLong sessionOffsetMillis(long monotonicNanos) {
        VideoAnchor anchor = videoAnchor;
        if (anchor == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Duration.ofNanos(monotonicNanos - anchor.startedNanos()).toMillis());
    }

    public OptionalLong currentOffsetMillis() {
        return sessionOffsetMillis(System.nanoTime());
    }

    private record VideoAnchor(Instant startedAt, long startedNanos) {
    }
}
