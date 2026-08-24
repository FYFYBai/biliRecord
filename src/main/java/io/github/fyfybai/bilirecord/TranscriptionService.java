package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

final class TranscriptionService {
    private static final Logger LOG = AppLog.get(TranscriptionService.class);
    private static final String FASTER_WHISPER_VERSION = "1.2.1";
    private static final Path ROOT = Path.of("data", "asr").toAbsolutePath().normalize();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Process activeProcess;

    boolean isReady() {
        return Files.isRegularFile(venvPython());
    }

    Result transcribe(SessionTimeline timeline, Config config, Progress progress)
            throws IOException, InterruptedException {
        if (timeline.summary().endedAt() == null) {
            throw new IOException("正在录制的 Session 不能生成转录");
        }
        if (timeline.segments().stream().noneMatch(segment -> Files.isRegularFile(segment.path()))) {
            throw new IOException("Session 中没有可读取的录像分段");
        }
        prepare(progress);
        Path script = extractScript();
        Path jobs = ROOT.resolve("jobs");
        Files.createDirectories(jobs);
        Path manifest = jobs.resolve(UUID.randomUUID() + ".json");
        writeManifest(manifest, timeline, config.language());

        List<String> command = List.of(
                venvPython().toString(),
                script.toString(),
                "--manifest", manifest.toString(),
                "--model", config.model(),
                "--device", config.device(),
                "--compute-type", config.computeType(),
                "--model-dir", ROOT.resolve("models").toString());
        List<TranscriptSegment> segments = new ArrayList<>();
        AtomicReference<String> stderrTail = new AtomicReference<>("");
        String detectedLanguage = config.language().isBlank() ? "auto" : config.language();
        try {
            Process process = start(command, false);
            Thread stderrReader = readStderr(process, stderrTail);
            try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JsonNode message = objectMapper.readTree(line);
                    String kind = message.path("kind").asText();
                    if (kind.equals("segment")) {
                        segments.add(new TranscriptSegment(
                                message.path("segmentPath").asText(),
                                message.path("startOffsetMs").asLong(),
                                message.path("endOffsetMs").asLong(),
                                message.path("text").asText(),
                                message.path("language").asText("auto")));
                    } else {
                        progress.update(
                                message.path("progress").asDouble(-1),
                                message.path("message").asText("正在转录"));
                        if (kind.equals("complete")) {
                            detectedLanguage = message.path("language").asText(detectedLanguage);
                        }
                    }
                }
            }
            int exitCode = waitFor(process);
            stderrReader.join();
            if (exitCode != 0) {
                throw new IOException("转录进程退出，代码 " + exitCode + ": " + stderrTail.get());
            }
        } finally {
            activeProcess = null;
            Files.deleteIfExists(manifest);
        }
        new TranscriptStore().replace(
                timeline.summary().directory(), segments, config.model(), detectedLanguage);
        return new Result(List.copyOf(segments), detectedLanguage);
    }

    void cancel() {
        Process process = activeProcess;
        if (process != null) {
            process.destroy();
        }
    }

    private void prepare(Progress progress) throws IOException, InterruptedException {
        Files.createDirectories(ROOT);
        extractScript();
        if (!Files.isRegularFile(venvPython())) {
            progress.update(-1, "正在创建本地语音识别环境");
            List<String> python = findPython();
            List<String> command = new ArrayList<>(python);
            command.addAll(List.of("-m", "venv", ROOT.resolve("venv").toString()));
            runCommand(command);
        }
        Path marker = ROOT.resolve("venv").resolve("faster-whisper-" + FASTER_WHISPER_VERSION);
        if (!Files.exists(marker)) {
            progress.update(-1, "正在安装 faster-whisper（仅首次需要）");
            runCommand(List.of(
                    venvPython().toString(), "-m", "pip", "install",
                    "--disable-pip-version-check", "faster-whisper==" + FASTER_WHISPER_VERSION));
            Files.createFile(marker);
        }
    }

    private void runCommand(List<String> command)
            throws IOException, InterruptedException {
        Process process = start(command, true);
        String tail = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail = line;
                LOG.info(line);
            }
        }
        int exitCode = waitFor(process);
        activeProcess = null;
        if (exitCode != 0) {
            throw new IOException("命令执行失败，代码 " + exitCode + ": " + tail);
        }
    }

    private Process start(List<String> command, boolean mergeError) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(mergeError);
        Process process = builder.start();
        activeProcess = process;
        return process;
    }

    private Thread readStderr(
            Process process,
            AtomicReference<String> tail) {
        return Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = process.errorReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    tail.set(line);
                    LOG.info(line);
                }
            } catch (IOException exception) {
                LOG.fine("ASR stderr reader closed: " + exception.getMessage());
            }
        });
    }

    private int waitFor(Process process) throws InterruptedException {
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            process.destroy();
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            throw exception;
        }
    }

    private void writeManifest(Path path, SessionTimeline timeline, String language)
            throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("language", language);
        ArrayNode files = root.putArray("segments");
        for (SessionSegment segment : timeline.segments()) {
            if (!Files.isRegularFile(segment.path())) {
                continue;
            }
            ObjectNode item = files.addObject();
            item.put("path", segment.path().toString());
            item.put("relativePath", timeline.summary().directory()
                    .toAbsolutePath().normalize()
                    .relativize(segment.path().toAbsolutePath().normalize()).toString());
            item.put("offsetMs", segment.startedOffsetMs());
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
    }

    private Path extractScript() throws IOException {
        Path script = ROOT.resolve("transcribe.py");
        Files.createDirectories(script.getParent());
        try (InputStream source = TranscriptionService.class.getResourceAsStream("transcribe.py")) {
            if (source == null) {
                throw new IOException("缺少内置转录脚本");
            }
            Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
        }
        return script;
    }

    private static List<String> findPython() throws IOException, InterruptedException {
        List<List<String>> candidates = List.of(
                List.of("py", "-3.13"),
                List.of("py", "-3"),
                List.of("python"));
        for (List<String> candidate : candidates) {
            List<String> command = new ArrayList<>(candidate);
            command.addAll(List.of("-c", "import sys; assert sys.version_info >= (3, 9)"));
            try {
                Process process = new ProcessBuilder(command).start();
                if (process.waitFor() == 0) {
                    return candidate;
                }
            } catch (IOException ignored) {
                // Try the next standard Python launcher.
            }
        }
        throw new IOException("未找到 Python 3.9 或更高版本");
    }

    private static Path venvPython() {
        Path windows = ROOT.resolve("venv").resolve("Scripts").resolve("python.exe");
        return Files.exists(windows)
                ? windows
                : ROOT.resolve("venv").resolve("bin").resolve("python");
    }

    record Config(String model, String device, String computeType, String language) {
    }

    record Result(List<TranscriptSegment> segments, String language) {
    }

    @FunctionalInterface
    interface Progress {
        void update(double fraction, String message);
    }
}
