package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

public record StorageStats(long sessionBytes, long usableBytes, long totalBytes) {
    public static StorageStats read(Path sessionDirectory) throws IOException {
        long sessionBytes;
        try (var paths = Files.walk(sessionDirectory)) {
            sessionBytes = paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            return 0;
                        }
                    })
                    .sum();
        }
        FileStore store = Files.getFileStore(sessionDirectory);
        return new StorageStats(sessionBytes, store.getUsableSpace(), store.getTotalSpace());
    }

    public double freeRatio() {
        return totalBytes == 0 ? 0 : (double) usableBytes / totalBytes;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return "%.1f %s".formatted(value, units[unit]);
    }
}
