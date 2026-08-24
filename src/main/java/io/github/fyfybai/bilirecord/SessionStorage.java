package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class SessionStorage implements AutoCloseable {
    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final Path directory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BufferedWriter rawEvents;
    private final Connection database;
    private final PreparedStatement insertEvent;
    private final PreparedStatement insertDanmaku;

    private SessionStorage(Path directory, long roomId) throws IOException {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
            rawEvents = Files.newBufferedWriter(
                    directory.resolve("raw-events.jsonl"),
                    StandardCharsets.UTF_8);
            database = DriverManager.getConnection(
                    "jdbc:sqlite:" + directory.resolve("timeline.sqlite").toAbsolutePath());
            initializeSchema(roomId);
            insertEvent = database.prepareStatement(
                    "INSERT INTO event(type, received_timestamp) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            insertDanmaku = database.prepareStatement(
                    "INSERT INTO danmaku(event_id, uid, username, text) VALUES (?, ?, ?, ?)");
        } catch (SQLException exception) {
            throw new IOException("Could not initialize session database", exception);
        }
    }

    public static SessionStorage create(long roomId) throws IOException {
        Path directory = Path.of(
                "recordings",
                "room_" + roomId,
                DIRECTORY_TIME.format(LocalDateTime.now()));
        return new SessionStorage(directory, roomId);
    }

    static SessionStorage create(Path directory, long roomId) throws IOException {
        return new SessionStorage(directory, roomId);
    }

    public synchronized void append(DanmakuEvent event) throws IOException {
        ObjectNode line = objectMapper.createObjectNode();
        line.put("receivedTimestamp", event.receivedAt().toEpochMilli());
        line.set("event", event.raw());
        rawEvents.write(objectMapper.writeValueAsString(line));
        rawEvents.newLine();
        rawEvents.flush();

        try {
            insertEvent.setString(1, event.type());
            insertEvent.setLong(2, event.receivedAt().toEpochMilli());
            insertEvent.executeUpdate();
            if (event.message() != null) {
                try (ResultSet keys = insertEvent.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("SQLite returned no event ID");
                    }
                    insertDanmaku.setLong(1, keys.getLong(1));
                }
                insertDanmaku.setLong(2, event.message().uid());
                insertDanmaku.setString(3, event.message().username());
                insertDanmaku.setString(4, event.message().text());
                insertDanmaku.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IOException("Could not store danmaku event", exception);
        }
    }

    public Path directory() {
        return directory.toAbsolutePath().normalize();
    }

    @Override
    public void close() throws IOException {
        try {
            try (PreparedStatement statement = database.prepareStatement(
                    "UPDATE session SET ended_at = ? WHERE id = 1")) {
                statement.setString(1, Instant.now().toString());
                statement.executeUpdate();
            }
            insertDanmaku.close();
            insertEvent.close();
            database.close();
            rawEvents.close();
        } catch (SQLException exception) {
            throw new IOException("Could not close session database", exception);
        }
    }

    private void initializeSchema(long roomId) throws SQLException {
        try (Statement statement = database.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                    CREATE TABLE session (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        room_id INTEGER NOT NULL,
                        started_at TEXT NOT NULL,
                        ended_at TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL,
                        received_timestamp INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE danmaku (
                        event_id INTEGER PRIMARY KEY REFERENCES event(id),
                        uid INTEGER NOT NULL,
                        username TEXT NOT NULL,
                        text TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX event_received_timestamp_idx ON event(received_timestamp)");
            statement.executeUpdate("CREATE INDEX danmaku_uid_idx ON danmaku(uid)");
        }
        try (PreparedStatement statement = database.prepareStatement(
                "INSERT INTO session(id, room_id, started_at) VALUES (1, ?, ?)")) {
            statement.setLong(1, roomId);
            statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        }
    }
}
