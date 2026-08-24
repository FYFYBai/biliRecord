package io.github.fyfybai.bilirecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RecordingHandle implements AutoCloseable {
    private final Process process;
    private final Thread outputReader;
    private final CompletableFuture<RecordingStart> firstProgress = new CompletableFuture<>();
    private final SessionClock clock;
    private final Path outputPattern;
    private final Path segmentList;
    private volatile Duration latestProgress = Duration.ZERO;
    private volatile long lastProgressAdvanceNanos = System.nanoTime();

    RecordingHandle(Process process, SessionClock clock, Path output, Path logFile) throws IOException {
        this(process, clock, output, logFile, null);
    }

    RecordingHandle(
            Process process,
            SessionClock clock,
            Path outputPattern,
            Path logFile,
            Path segmentList) throws IOException {
        this.process = process;
        this.clock = clock;
        this.outputPattern = outputPattern;
        this.segmentList = segmentList;
        BufferedWriter log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
        outputReader = Thread.ofVirtual().name("ffmpeg-progress").start(() -> readOutput(log));
    }

    public RecordingStart awaitStarted(Duration timeout) throws IOException, InterruptedException {
        try {
            return firstProgress.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new IOException("FFmpeg did not receive media within " + timeout.toSeconds() + " seconds", exception);
        } catch (ExecutionException exception) {
            throw new IOException("FFmpeg exited before receiving media", exception.getCause());
        }
    }

    public Path output() {
        return segmentPath(0);
    }

    public Path segmentPath(int index) {
        if (segmentList == null) {
            return outputPattern.toAbsolutePath().normalize();
        }
        String filename = outputPattern.getFileName().toString().formatted(index);
        return outputPattern.resolveSibling(filename).toAbsolutePath().normalize();
    }

    public List<RecordingSegment> completedSegments() throws IOException {
        if (segmentList == null || !Files.exists(segmentList)) {
            return List.of();
        }
        List<RecordingSegment> completed = new ArrayList<>();
        for (String line : Files.readAllLines(segmentList, StandardCharsets.UTF_8)) {
            int endedSeparator = line.lastIndexOf(',');
            int startedSeparator = line.lastIndexOf(',', endedSeparator - 1);
            if (startedSeparator < 0 || endedSeparator < 0) {
                continue;
            }
            try {
                long startedMs = secondsToMillis(line.substring(startedSeparator + 1, endedSeparator));
                long endedMs = secondsToMillis(line.substring(endedSeparator + 1));
                completed.add(new RecordingSegment(
                        segmentPath(completed.size()), startedMs, endedMs));
            } catch (NumberFormatException ignored) {
                // FFmpeg may still be appending the final line while it is read.
            }
        }
        return List.copyOf(completed);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long videoPositionMillis() {
        return latestProgress.toMillis();
    }

    public boolean isStalled(Duration timeout) {
        return process.isAlive()
                && firstProgress.isDone()
                && System.nanoTime() - lastProgressAdvanceNanos >= timeout.toNanos();
    }

    public int awaitExit() throws InterruptedException {
        return process.waitFor();
    }

    public void stop() throws InterruptedException {
        if (!process.isAlive()) {
            outputReader.join();
            return;
        }
        try {
            var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            writer.write("q\n");
            writer.flush();
        } catch (IOException ignored) {
            process.destroy();
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
        outputReader.join();
    }

    @Override
    public void close() throws InterruptedException {
        stop();
    }

    private void readOutput(BufferedWriter log) {
        try (log; BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.write(line);
                log.newLine();
                if (line.startsWith("out_time_us=")) {
                    anchor(line.substring("out_time_us=".length()));
                }
            }
            log.flush();
            if (!firstProgress.isDone()) {
                firstProgress.completeExceptionally(
                        new IOException("FFmpeg exited with code " + process.waitFor()));
            }
        } catch (Exception exception) {
            firstProgress.completeExceptionally(exception);
        }
    }

    private void anchor(String value) {
        if ("N/A".equals(value)) {
            return;
        }
        try {
            Duration position = Duration.ofNanos(Long.parseLong(value) * 1_000L);
            if (position.compareTo(latestProgress) > 0) {
                latestProgress = position;
                lastProgressAdvanceNanos = System.nanoTime();
            }
            if (!firstProgress.isDone()) {
                Instant observedAt = Instant.now();
                long observedNanos = System.nanoTime();
                clock.anchorVideo(position, observedAt, observedNanos);
                long segmentStart = clock.sessionOffsetMillis(observedNanos).orElseThrow()
                        - position.toMillis();
                firstProgress.complete(new RecordingStart(position, segmentStart));
            }
        } catch (NumberFormatException ignored) {
            // A later progress record can still provide a numeric timestamp.
        }
    }

    private static long secondsToMillis(String value) {
        return new BigDecimal(value).movePointRight(3).longValue();
    }
}
