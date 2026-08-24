package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LiveRecordingSession implements AutoCloseable {
    private final RoomInfo room;
    private final SessionClock clock;
    private final SessionStorage storage;
    private final StreamResolver streamResolver = new StreamResolver();
    private final DanmakuInfoResolver danmakuResolver = new DanmakuInfoResolver();
    private final AtomicBoolean closed = new AtomicBoolean();

    private RecordingHandle recorder;
    private DanmakuClient danmaku;
    private long segmentId;
    private long segmentStartedOffsetMs;
    private int segmentCount;

    private LiveRecordingSession(RoomInfo room, SessionClock clock, SessionStorage storage) {
        this.room = room;
        this.clock = clock;
        this.storage = storage;
    }

    public static LiveRecordingSession start(RoomInfo room) throws IOException, InterruptedException {
        SessionClock clock = SessionClock.start();
        SessionStorage storage = SessionStorage.create(room, clock);
        LiveRecordingSession session = new LiveRecordingSession(room, clock, storage);
        try {
            session.startNextSegment();
            try {
                session.connectDanmaku();
            } catch (IOException exception) {
                System.err.println("Danmaku connection will be retried: " + exception.getMessage());
            }
            return session;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            session.closeAfterFailure(exception);
            throw exception;
        }
    }

    public Path directory() {
        return storage.directory();
    }

    public synchronized void recoverIfNeeded() throws IOException, InterruptedException {
        if (closed.get()) {
            return;
        }
        if (recorder == null || !recorder.isAlive()) {
            finishCurrentSegment();
            startNextSegment();
            System.out.println("recording recovered with a fresh stream URL and segment");
        }
        if (danmaku == null || !danmaku.isOpen()) {
            disconnectDanmaku();
            connectDanmaku();
            System.out.println("danmaku WebSocket reconnected");
        }
    }

    @Override
    public synchronized void close() throws IOException, InterruptedException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        disconnectDanmaku();
        try {
            finishCurrentSegment();
        } finally {
            storage.close();
        }
    }

    private void startNextSegment() throws IOException, InterruptedException {
        PlayInfo playInfo = streamResolver.resolve(room.roomId());
        if (playInfo.status() != RoomStatus.LIVE) {
            throw new IOException("Room went offline before recording started");
        }
        StreamVariant selected;
        try {
            selected = new StreamSelector().selectPrefer1080p(playInfo);
        } catch (IllegalStateException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
        Path videoDirectory = storage.directory().resolve("video");
        Path logDirectory = storage.directory().resolve("logs");
        Files.createDirectories(videoDirectory);
        Files.createDirectories(logDirectory);
        Path video = nextVideoPath(videoDirectory);
        IOException lastFailure = null;
        for (URI streamUrl : selected.urls()) {
            RecordingHandle candidate = null;
            try {
                candidate = new RecorderManager().start(
                        streamUrl,
                        room.roomId(),
                        video,
                        logDirectory.resolve("%03d-ffmpeg.log".formatted(segmentCount)),
                        clock);
                RecordingStart started = candidate.awaitStarted(Duration.ofSeconds(20));
                if (segmentCount == 0) {
                    storage.setVideoStartedAt(clock.videoStartedAt().orElseThrow());
                }
                segmentId = storage.startSegment(video, started.sessionOffsetMs());
                segmentStartedOffsetMs = started.sessionOffsetMs();
                recorder = candidate;
                segmentCount++;
                return;
            } catch (IOException exception) {
                lastFailure = exception;
                if (candidate != null) {
                    candidate.stop();
                }
            }
        }
        throw new IOException("All CDN candidates failed", lastFailure);
    }

    private Path nextVideoPath(Path videoDirectory) {
        long offsetSeconds = segmentCount == 0
                ? 0
                : Math.max(0, clock.currentOffsetMillis().orElse(0) / 1_000);
        String baseName = "%06d".formatted(offsetSeconds);
        Path candidate = videoDirectory.resolve(baseName + ".mkv");
        for (int suffix = 2; Files.exists(candidate); suffix++) {
            candidate = videoDirectory.resolve(baseName + "_" + suffix + ".mkv");
        }
        return candidate;
    }

    private void finishCurrentSegment() throws IOException, InterruptedException {
        if (recorder == null) {
            return;
        }
        recorder.stop();
        storage.finishSegment(
                segmentId,
                segmentStartedOffsetMs + recorder.videoPositionMillis());
        recorder = null;
        segmentId = 0;
    }

    private void connectDanmaku() throws IOException, InterruptedException {
        DanmakuInfo info = danmakuResolver.resolve(room.roomId());
        DanmakuClient candidate = new DanmakuClient();
        try {
            candidate.connect(info, event -> {
                storage.append(event);
                DanmakuMessage message = event.message();
                if (message != null) {
                    long offset = clock.sessionOffsetMillis(event.receivedMonotonicNanos()).orElse(0);
                    System.out.printf("[%s] [%s(%d)] %s%n",
                            formatOffset(offset), message.username(), message.uid(), message.text());
                }
            });
            danmaku = candidate;
        } catch (IOException | InterruptedException exception) {
            candidate.close();
            throw exception;
        }
    }

    private void disconnectDanmaku() {
        if (danmaku != null) {
            danmaku.close();
            danmaku = null;
        }
    }

    private static String formatOffset(long offsetMs) {
        long totalSeconds = Math.max(0, offsetMs) / 1_000;
        return "%02d:%02d:%02d.%03d".formatted(
                totalSeconds / 3_600,
                totalSeconds / 60 % 60,
                totalSeconds % 60,
                Math.max(0, offsetMs) % 1_000);
    }

    private void closeAfterFailure(Exception failure) {
        try {
            close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
