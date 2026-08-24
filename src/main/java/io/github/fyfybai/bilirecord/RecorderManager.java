package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class RecorderManager {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Duration SEGMENT_DURATION = Duration.ofMinutes(30);

    public Path record(URI streamUrl, long roomId, Duration duration) throws IOException, InterruptedException {
        Path directory = Path.of("recordings", "room_" + roomId);
        Files.createDirectories(directory);
        Path output = directory.resolve(FILE_TIME.format(LocalDateTime.now()) + ".mkv");

        List<String> command = new ArrayList<>(List.of(
                "ffmpeg",
                "-y",
                "-hide_banner",
                "-loglevel", "warning",
                "-user_agent", "biliRecord/0.1",
                "-referer", "https://live.bilibili.com/" + roomId,
                "-i", streamUrl.toString(),
                "-t", Long.toString(duration.toSeconds()),
                "-map", "0:v:0",
                "-map", "0:a?",
                "-c", "copy",
                output.toString()));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg failed with exit code " + exitCode + ": " + log.strip());
        }
        return output.toAbsolutePath().normalize();
    }

    public RecordingHandle start(
            URI streamUrl,
            long roomId,
            Path output,
            Path logFile,
            SessionClock clock) throws IOException {
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.createDirectories(logFile.toAbsolutePath().normalize().getParent());
        Path outputPattern = segmentPattern(output);
        Path segmentList = logFile.resolveSibling(
                logFile.getFileName().toString().replace("-ffmpeg.log", "-segments.csv"));
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg",
                "-y",
                "-hide_banner",
                "-loglevel", "warning",
                "-stats_period", "0.25",
                "-progress", "pipe:1",
                "-nostats",
                "-user_agent", "biliRecord/0.1",
                "-referer", "https://live.bilibili.com/" + roomId,
                "-i", streamUrl.toString(),
                "-map", "0:v:0",
                "-map", "0:a?",
                "-c", "copy",
                "-f", "segment",
                "-segment_time", Long.toString(SEGMENT_DURATION.toSeconds()),
                "-segment_list", segmentList.toString(),
                "-segment_list_type", "csv",
                "-reset_timestamps", "1",
                outputPattern.toString()));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        return new RecordingHandle(process, clock, outputPattern, logFile, segmentList);
    }

    static Path segmentPattern(Path output) {
        String filename = output.getFileName().toString();
        int extension = filename.lastIndexOf('.');
        String pattern = extension < 0
                ? filename + "_%03d"
                : filename.substring(0, extension) + "_%03d" + filename.substring(extension);
        return output.resolveSibling(pattern);
    }

    static Path firstSegmentPath(Path output) {
        Path pattern = segmentPattern(output);
        return pattern.resolveSibling(pattern.getFileName().toString().formatted(0));
    }
}
