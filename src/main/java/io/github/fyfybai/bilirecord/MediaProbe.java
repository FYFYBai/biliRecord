package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class MediaProbe {
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public MediaInfo probe(URI streamUrl, long roomId) throws IOException, InterruptedException {
        List<String> command = List.of(
                "ffprobe",
                "-v", "error",
                "-user_agent", "biliRecord/0.1",
                "-referer", "https://live.bilibili.com/" + roomId,
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,codec_name",
                "-of", "json",
                streamUrl.toString());
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        if (!process.waitFor(PROBE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("FFprobe timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("FFprobe failed: " + output.strip());
        }

        JsonNode stream = objectMapper.readTree(output).path("streams").path(0);
        if (!stream.isObject()) {
            throw new IOException("FFprobe returned no video stream");
        }
        return new MediaInfo(
                stream.path("width").asInt(),
                stream.path("height").asInt(),
                stream.path("codec_name").asText("unknown"));
    }
}
