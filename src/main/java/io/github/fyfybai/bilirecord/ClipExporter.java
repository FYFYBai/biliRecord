package io.github.fyfybai.bilirecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ClipExporter {
    Result export(
            SessionTimeline timeline,
            long startMs,
            long endMs,
            Path output,
            Progress progress) throws IOException, InterruptedException {
        List<Part> parts = parts(timeline, startMs, endMs);
        if (parts.isEmpty()) {
            throw new IOException("所选时间范围内没有可导出的录像");
        }
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Path parent = absoluteOutput.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path concatFile = Files.createTempFile("bilirecord-export-", ".ffconcat");
        long mediaDurationMs = parts.stream().mapToLong(Part::durationMs).sum();
        try {
            writeConcatFile(concatFile, parts);
            runFfmpeg(concatFile, absoluteOutput, mediaDurationMs, progress);
            return new Result(absoluteOutput, mediaDurationMs, parts.size());
        } finally {
            Files.deleteIfExists(concatFile);
        }
    }

    static List<Part> parts(SessionTimeline timeline, long startMs, long endMs) {
        List<Part> parts = new ArrayList<>();
        for (SessionSegment segment : timeline.segments()) {
            long overlapStart = Math.max(startMs, segment.startedOffsetMs());
            long overlapEnd = Math.min(endMs, segment.endedOffsetMs());
            if (overlapStart < overlapEnd && Files.isRegularFile(segment.path())) {
                parts.add(new Part(
                        segment.path(),
                        overlapStart - segment.startedOffsetMs(),
                        overlapEnd - segment.startedOffsetMs()));
            }
        }
        return List.copyOf(parts);
    }

    private static void writeConcatFile(Path concatFile, List<Part> parts) throws IOException {
        StringBuilder content = new StringBuilder("ffconcat version 1.0\n");
        for (Part part : parts) {
            String path = part.path().toAbsolutePath().normalize().toString()
                    .replace("\\", "/")
                    .replace("'", "'\\''");
            content.append("file '").append(path).append("'\n");
            content.append("inpoint ").append(seconds(part.startMs())).append('\n');
            content.append("outpoint ").append(seconds(part.endMs())).append('\n');
        }
        Files.writeString(concatFile, content, StandardCharsets.UTF_8);
    }

    private static void runFfmpeg(
            Path concatFile,
            Path output,
            long durationMs,
            Progress progress) throws IOException, InterruptedException {
        List<String> command = List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel", "error",
                "-y",
                "-segment_time_metadata", "1",
                "-f", "concat",
                "-safe", "0",
                "-i", concatFile.toString(),
                "-vf", "select=concatdec_select,setpts=PTS-STARTPTS",
                "-af", "aselect=concatdec_select,asetpts=PTS-STARTPTS",
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "20",
                "-c:a", "aac",
                "-b:a", "192k",
                "-movflags", "+faststart",
                "-progress", "pipe:1",
                "-nostats",
                output.toString());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        ArrayDeque<String> outputTail = new ArrayDeque<>();
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("out_time_us=")) {
                    long encodedUs = parseLong(line.substring("out_time_us=".length()));
                    progress.update(Math.min(1, encodedUs / (durationMs * 1_000.0)));
                }
                outputTail.addLast(line);
                while (outputTail.size() > 20) {
                    outputTail.removeFirst();
                }
            }
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException exception) {
            process.destroy();
            throw exception;
        }
        if (exitCode != 0) {
            Files.deleteIfExists(output);
            throw new IOException("FFmpeg 导出失败：" + String.join(" | ", outputTail));
        }
        progress.update(1);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1_000.0);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    record Part(Path path, long startMs, long endMs) {
        long durationMs() {
            return endMs - startMs;
        }
    }

    record Result(Path output, long durationMs, int parts) {
    }

    @FunctionalInterface
    interface Progress {
        void update(double fraction);
    }
}
