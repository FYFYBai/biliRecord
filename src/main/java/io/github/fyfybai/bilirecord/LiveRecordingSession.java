package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LiveRecordingSession implements AutoCloseable {
    private final SessionStorage storage;
    private final RecordingHandle recorder;
    private final DanmakuClient danmaku;
    private final long segmentId;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LiveRecordingSession(
            SessionStorage storage,
            RecordingHandle recorder,
            DanmakuClient danmaku,
            long segmentId) {
        this.storage = storage;
        this.recorder = recorder;
        this.danmaku = danmaku;
        this.segmentId = segmentId;
    }

    public static LiveRecordingSession start(RoomInfo room) throws IOException, InterruptedException {
        SessionClock clock = SessionClock.start();
        SessionStorage storage = SessionStorage.create(room, clock);
        RecordingHandle recorder = null;
        DanmakuClient danmaku = null;
        try {
            PlayInfo playInfo = new StreamResolver().resolve(room.roomId());
            if (playInfo.status() != RoomStatus.LIVE) {
                throw new IOException("Room went offline before recording started");
            }
            StreamVariant selected = new StreamSelector().selectPrefer1080p(playInfo);
            Path videoDirectory = storage.directory().resolve("video");
            Path logDirectory = storage.directory().resolve("logs");
            Files.createDirectories(videoDirectory);
            Files.createDirectories(logDirectory);
            Path video = videoDirectory.resolve("000000.mkv");
            recorder = new RecorderManager().start(
                    selected.urls().getFirst(),
                    room.roomId(),
                    video,
                    logDirectory.resolve("ffmpeg.log"),
                    clock);
            recorder.awaitStarted(Duration.ofSeconds(20));
            storage.setVideoStartedAt(clock.videoStartedAt().orElseThrow());
            long segmentId = storage.startSegment(video, 0);

            DanmakuInfo danmakuInfo = new DanmakuInfoResolver().resolve(room.roomId());
            danmaku = new DanmakuClient();
            danmaku.connect(danmakuInfo, event -> {
                storage.append(event);
                DanmakuMessage message = event.message();
                if (message != null) {
                    long offset = clock.sessionOffsetMillis(event.receivedMonotonicNanos()).orElse(0);
                    System.out.printf("[%s] [%s(%d)] %s%n",
                            formatOffset(offset), message.username(), message.uid(), message.text());
                }
            });
            return new LiveRecordingSession(storage, recorder, danmaku, segmentId);
        } catch (IOException | InterruptedException | RuntimeException exception) {
            closePartially(danmaku, recorder, storage, exception);
            throw exception;
        }
    }

    public Path directory() {
        return storage.directory();
    }

    @Override
    public void close() throws IOException, InterruptedException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        danmaku.close();
        try {
            recorder.stop();
            storage.finishSegment(segmentId, recorder.videoPositionMillis());
        } finally {
            storage.close();
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

    private static void closePartially(
            DanmakuClient danmaku,
            RecordingHandle recorder,
            SessionStorage storage,
            Exception failure) {
        try {
            if (danmaku != null) {
                danmaku.close();
            }
            try {
                if (recorder != null) {
                    recorder.stop();
                }
            } finally {
                storage.close();
            }
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
