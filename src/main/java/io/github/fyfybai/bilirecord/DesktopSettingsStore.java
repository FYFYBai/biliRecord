package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DesktopSettingsStore {
    private static final Path DEFAULT_PATH = Path.of("data", "settings.json");

    private final Path path;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    DesktopSettingsStore() {
        this(DEFAULT_PATH);
    }

    DesktopSettingsStore(Path path) {
        this.path = path;
    }

    DesktopSettings load() throws IOException {
        return Files.exists(path)
                ? objectMapper.readValue(path.toFile(), DesktopSettings.class)
                : new DesktopSettings("");
    }

    void save(DesktopSettings settings) throws IOException {
        Files.createDirectories(path.toAbsolutePath().normalize().getParent());
        objectMapper.writeValue(path.toFile(), settings);
    }
}
