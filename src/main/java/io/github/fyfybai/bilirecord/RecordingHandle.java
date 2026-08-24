package io.github.fyfybai.bilirecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class RecordingHandle implements AutoCloseable {
    private final Process process;
    private final Thread outputReader;
    private final CompletableFuture<Duration> firstProgress = new CompletableFuture<>();
    private final SessionClock clock;
    private final Path output;
    private volatile Duration latestProgress = Duration.ZERO;

    RecordingHandle(Process process, SessionClock clock, Path output, Path logFile) throws IOException {
        this.process = process;
        this.clock = clock;
        this.output = output;
        BufferedWriter log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
        outputReader = Thread.ofVirtual().name("ffmpeg-progress").start(() -> readOutput(log));
    }

    public Duration awaitStarted(Duration timeout) throws IOException, InterruptedException {
        try {
            return firstProgress.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new IOException("FFmpeg did not receive media within " + timeout.toSeconds() + " seconds", exception);
        } catch (ExecutionException exception) {
            throw new IOException("FFmpeg exited before receiving media", exception.getCause());
        }
    }

    public Path output() {
        return output.toAbsolutePath().normalize();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long videoPositionMillis() {
        return latestProgress.toMillis();
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
            latestProgress = position;
            if (!firstProgress.isDone()) {
                clock.anchorVideo(position);
                firstProgress.complete(position);
            }
        } catch (NumberFormatException ignored) {
            // A later progress record can still provide a numeric timestamp.
        }
    }
}
