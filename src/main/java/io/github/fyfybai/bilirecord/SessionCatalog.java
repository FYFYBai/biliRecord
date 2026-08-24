package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

final class SessionCatalog {
    private static final Logger LOG = AppLog.get(SessionCatalog.class);
    private final Path recordingsDirectory;

    SessionCatalog() {
        this(Path.of("recordings"));
    }

    SessionCatalog(Path recordingsDirectory) {
        this.recordingsDirectory = recordingsDirectory;
    }

    List<SessionSummary> recent(int limit) throws IOException {
        if (!Files.isDirectory(recordingsDirectory)) {
            return List.of();
        }
        List<Path> databases;
        try (var paths = Files.walk(recordingsDirectory, 3)) {
            databases = paths
                    .filter(path -> path.getFileName().toString().equals("timeline.sqlite"))
                    .sorted(Comparator.comparingLong(SessionCatalog::lastModified).reversed())
                    .limit(limit)
                    .toList();
        }
        List<SessionSummary> sessions = new ArrayList<>();
        for (Path database : databases) {
            try {
                read(database).ifPresent(sessions::add);
            } catch (Exception exception) {
                LOG.log(Level.FINE, "Skipping unreadable session database " + database, exception);
            }
        }
        return sessions;
    }

    private static Optional<SessionSummary> read(Path database) throws Exception {
        Path directory = database.getParent();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            if (!hasTable(connection, "session") || !hasTable(connection, "segment")) {
                return Optional.empty();
            }
            try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT session.room_id, session.uid, session.title,
                            session.started_at, session.ended_at,
                            (SELECT COUNT(*) FROM segment) AS segments
                     FROM session
                     WHERE session.id = 1
                      """)) {
                int segments = result.getInt("segments");
                if (segments == 0) {
                    return Optional.empty();
                }
                String endedAt = result.getString("ended_at");
                return Optional.of(new SessionSummary(
                        result.getLong("room_id"),
                        result.getLong("uid"),
                        result.getString("title"),
                        Instant.parse(result.getString("started_at")),
                        endedAt == null ? null : Instant.parse(endedAt),
                        segments,
                        directorySize(directory),
                        directory.toAbsolutePath().normalize()));
            }
        }
    }

    private static boolean hasTable(Connection connection, String name) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static long directorySize(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException exception) {
                            return 0;
                        }
                    })
                    .sum();
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return 0;
        }
    }
}
