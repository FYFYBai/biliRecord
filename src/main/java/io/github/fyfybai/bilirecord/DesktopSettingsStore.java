package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class DesktopSettingsStore {
    private static final Path DEFAULT_PATH = Path.of("data", "settings.json");
    private static final Object FILE_LOCK = new Object();

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
        synchronized (FILE_LOCK) {
            return Files.exists(path)
                    ? objectMapper.readValue(path.toFile(), DesktopSettings.class)
                    : new DesktopSettings("", "");
        }
    }

    void save(DesktopSettings settings) throws IOException {
        synchronized (FILE_LOCK) {
            Files.createDirectories(path.toAbsolutePath().normalize().getParent());
            objectMapper.writeValue(path.toFile(), settings);
        }
    }

    void saveRoom(String room) throws IOException {
        synchronized (FILE_LOCK) {
            DesktopSettings current = load();
            save(new DesktopSettings(room, current.exportDirectory()));
        }
    }

    void saveExportDirectory(String exportDirectory) throws IOException {
        synchronized (FILE_LOCK) {
            DesktopSettings current = load();
            save(new DesktopSettings(current.room(), exportDirectory));
        }
    }
}
