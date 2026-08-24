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
import java.util.OptionalLong;

public final class SessionStorage implements AutoCloseable {
    private static final DateTimeFormatter DIRECTORY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    private final Path directory;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BufferedWriter rawEvents;
    private final Connection database;
    private final SessionClock clock;
    private final PreparedStatement insertEvent;
    private final PreparedStatement insertDanmaku;

    private SessionStorage(Path directory, RoomInfo room, SessionClock clock) throws IOException {
        this.directory = directory;
        this.clock = clock;
        try {
            Files.createDirectories(directory);
            rawEvents = Files.newBufferedWriter(
                    directory.resolve("raw-events.jsonl"),
                    StandardCharsets.UTF_8);
            database = DriverManager.getConnection(
                    "jdbc:sqlite:" + directory.resolve("timeline.sqlite").toAbsolutePath());
            initializeSchema(room);
            insertEvent = database.prepareStatement(
                    """
                    INSERT INTO event(type, server_timestamp, received_timestamp, session_offset_ms)
                    VALUES (?, ?, ?, ?)
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            insertDanmaku = database.prepareStatement(
                    "INSERT INTO danmaku(event_id, uid, username, text) VALUES (?, ?, ?, ?)");
        } catch (SQLException exception) {
            throw new IOException("Could not initialize session database", exception);
        }
    }

    public static SessionStorage create(long roomId) throws IOException {
        SessionClock clock = SessionClock.start();
        clock.anchorAtSessionStart();
        return create(new RoomInfo(roomId, 0, "", RoomStatus.LIVE), clock);
    }

    public static SessionStorage create(RoomInfo room, SessionClock clock) throws IOException {
        Path directory = Path.of(
                "recordings",
                "room_" + room.roomId(),
                DIRECTORY_TIME.format(LocalDateTime.now()));
        return new SessionStorage(directory, room, clock);
    }

    static SessionStorage create(Path directory, long roomId) throws IOException {
        SessionClock clock = SessionClock.start();
        clock.anchorAtSessionStart();
        return new SessionStorage(
                directory,
                new RoomInfo(roomId, 0, "", RoomStatus.LIVE),
                clock);
    }

    static SessionStorage create(Path directory, RoomInfo room, SessionClock clock) throws IOException {
        return new SessionStorage(directory, room, clock);
    }

    public synchronized void append(DanmakuEvent event) throws IOException {
        ObjectNode line = objectMapper.createObjectNode();
        if (event.serverTimestamp() == null) {
            line.putNull("serverTimestamp");
        } else {
            line.put("serverTimestamp", event.serverTimestamp());
        }
        line.put("receivedTimestamp", event.receivedAt().toEpochMilli());
        OptionalLong sessionOffset = clock.sessionOffsetMillis(event.receivedMonotonicNanos());
        if (sessionOffset.isPresent()) {
            line.put("sessionOffsetMs", sessionOffset.getAsLong());
        } else {
            line.putNull("sessionOffsetMs");
        }
        line.set("event", event.raw());
        rawEvents.write(objectMapper.writeValueAsString(line));
        rawEvents.newLine();
        rawEvents.flush();

        try {
            insertEvent.setString(1, event.type());
            if (event.serverTimestamp() == null) {
                insertEvent.setNull(2, java.sql.Types.BIGINT);
            } else {
                insertEvent.setLong(2, event.serverTimestamp());
            }
            insertEvent.setLong(3, event.receivedAt().toEpochMilli());
            if (sessionOffset.isPresent()) {
                insertEvent.setLong(4, sessionOffset.getAsLong());
            } else {
                insertEvent.setNull(4, java.sql.Types.BIGINT);
            }
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

    public synchronized void setVideoStartedAt(Instant startedAt) throws IOException {
        try (PreparedStatement statement = database.prepareStatement(
                "UPDATE session SET video_started_at = ? WHERE id = 1")) {
            statement.setString(1, startedAt.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IOException("Could not store video start time", exception);
        }
    }

    public synchronized long startSegment(Path path, long startedOffsetMs) throws IOException {
        try (PreparedStatement statement = database.prepareStatement(
                "INSERT INTO segment(path, started_offset_ms) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, directory().relativize(path.toAbsolutePath().normalize()).toString());
            statement.setLong(2, startedOffsetMs);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("SQLite returned no segment ID");
                }
                return keys.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IOException("Could not store recording segment", exception);
        }
    }

    public synchronized void finishSegment(long segmentId, long endedOffsetMs) throws IOException {
        try (PreparedStatement statement = database.prepareStatement(
                "UPDATE segment SET ended_offset_ms = ? WHERE id = ?")) {
            statement.setLong(1, endedOffsetMs);
            statement.setLong(2, segmentId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IOException("Could not finish recording segment", exception);
        }
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

    private void initializeSchema(RoomInfo room) throws SQLException {
        try (Statement statement = database.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                    CREATE TABLE session (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        room_id INTEGER NOT NULL,
                        uid INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        started_at TEXT NOT NULL,
                        video_started_at TEXT,
                        ended_at TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL,
                        server_timestamp INTEGER,
                        received_timestamp INTEGER NOT NULL,
                        session_offset_ms INTEGER
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
            statement.executeUpdate("""
                    CREATE TABLE segment (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        path TEXT NOT NULL,
                        started_offset_ms INTEGER NOT NULL,
                        ended_offset_ms INTEGER
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX event_received_timestamp_idx ON event(received_timestamp)");
            statement.executeUpdate(
                    "CREATE INDEX event_session_offset_idx ON event(session_offset_ms)");
            statement.executeUpdate("CREATE INDEX danmaku_uid_idx ON danmaku(uid)");
        }
        try (PreparedStatement statement = database.prepareStatement(
                """
                INSERT INTO session(id, room_id, uid, title, started_at)
                VALUES (1, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, room.roomId());
            statement.setLong(2, room.uid());
            statement.setString(3, room.title());
            statement.setString(4, clock.sessionStartedAt().toString());
            statement.executeUpdate();
        }
    }
}
