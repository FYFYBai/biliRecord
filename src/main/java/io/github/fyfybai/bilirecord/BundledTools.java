package io.github.fyfybai.bilirecord;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class BundledTools {
    private BundledTools() {
    }

    static String ffmpeg() {
        return executable("ffmpeg", "ffmpeg.exe").map(Path::toString).orElse("ffmpeg");
    }

    static String ffprobe() {
        return executable("ffmpeg", "ffprobe.exe").map(Path::toString).orElse("ffprobe");
    }

    static Optional<Path> vlcDirectory() {
        Path directory = applicationDirectory().resolve("tools").resolve("vlc");
        return Files.isRegularFile(directory.resolve("libvlc.dll"))
                ? Optional.of(directory)
                : Optional.empty();
    }

    static Optional<Path> python() {
        Path executable = applicationDirectory().resolve("tools").resolve("python").resolve("python.exe");
        return Files.isRegularFile(executable) ? Optional.of(executable) : Optional.empty();
    }

    private static Optional<Path> executable(String directory, String filename) {
        Path executable = applicationDirectory()
                .resolve("tools")
                .resolve(directory)
                .resolve("bin")
                .resolve(filename);
        return Files.isRegularFile(executable) ? Optional.of(executable) : Optional.empty();
    }

    private static Path applicationDirectory() {
        String launcher = System.getProperty("jpackage.app-path", "");
        if (!launcher.isBlank()) {
            return Path.of(launcher).toAbsolutePath().normalize().getParent().resolve("app");
        }
        try {
            Path codeSource = Path.of(Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            return Files.isDirectory(codeSource) ? codeSource : codeSource.getParent();
        } catch (URISyntaxException exception) {
            return Path.of(".").toAbsolutePath().normalize();
        }
    }
}
