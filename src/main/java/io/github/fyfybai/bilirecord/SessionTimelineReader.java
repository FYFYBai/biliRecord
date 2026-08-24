package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class SessionTimelineReader {
    SessionTimeline read(SessionSummary summary) throws IOException {
        Path database = summary.directory().resolve("timeline.sqlite");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath())) {
            List<SessionSegment> segments = readSegments(connection, summary.directory());
            List<TimelineEntry> entries = new ArrayList<>();
            if (hasTable(connection, "normalized_event")) {
                entries.addAll(readEvents(connection));
            }
            TranscriptionStatus transcription = TranscriptionStatus.empty();
            if (hasTable(connection, "transcript")) {
                entries.addAll(readTranscript(connection));
                transcription = readTranscriptionStatus(connection);
            }
            long durationMs = segments.stream()
                    .mapToLong(SessionSegment::endedOffsetMs)
                    .max()
                    .orElseGet(() -> entries.stream()
                            .mapToLong(TimelineEntry::endOffsetMs)
                            .max()
                            .orElse(0));
            return new SessionTimeline(summary, segments, entries, transcription, durationMs);
        } catch (SQLException exception) {
            throw new IOException("Could not load session timeline", exception);
        }
    }

    private static List<SessionSegment> readSegments(Connection connection, Path directory)
            throws SQLException {
        List<SessionSegment> segments = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT id, path, started_offset_ms,
                            COALESCE(ended_offset_ms, started_offset_ms) AS ended_offset_ms
                     FROM segment
                     ORDER BY started_offset_ms
                     """)) {
            while (result.next()) {
                segments.add(new SessionSegment(
                        result.getLong("id"),
                        directory.resolve(result.getString("path")).toAbsolutePath().normalize(),
                        result.getLong("started_offset_ms"),
                        result.getLong("ended_offset_ms")));
            }
        }
        return List.copyOf(segments);
    }

    private static List<TimelineEntry> readEvents(Connection connection) throws SQLException {
        List<TimelineEntry> entries = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT event.session_offset_ms, normalized_event.kind,
                            normalized_event.uid, normalized_event.username,
                            normalized_event.content, normalized_event.item_name,
                            normalized_event.quantity, normalized_event.price,
                            normalized_event.price_unit, normalized_event.guard_level,
                            normalized_event.purchase_kind, normalized_event.title,
                            normalized_event.area
                     FROM event
                     JOIN normalized_event ON normalized_event.event_id = event.id
                     WHERE event.session_offset_ms IS NOT NULL
                     ORDER BY event.session_offset_ms
                     """)) {
            while (result.next()) {
                EventKind kind = EventKind.valueOf(result.getString("kind"));
                NormalizedEvent event = new NormalizedEvent(
                        kind,
                        nullableLong(result, "uid"),
                        result.getString("username"),
                        result.getString("content"),
                        result.getString("item_name"),
                        nullableInteger(result, "quantity"),
                        nullableLong(result, "price"),
                        result.getString("price_unit"),
                        nullableInteger(result, "guard_level"),
                        nullablePurchaseKind(result.getString("purchase_kind")),
                        result.getString("title"),
                        result.getString("area"));
                long offsetMs = result.getLong("session_offset_ms");
                entries.add(new TimelineEntry(
                        offsetMs,
                        offsetMs,
                        TimelineSource.EVENT,
                        label(kind),
                        event.username() == null ? "" : event.username(),
                        content(event)));
            }
        }
        return List.copyOf(entries);
    }

    private static List<TimelineEntry> readTranscript(Connection connection) throws SQLException {
        List<TimelineEntry> entries = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT start_offset_ms, end_offset_ms, text
                     FROM transcript
                     ORDER BY start_offset_ms
                     """)) {
            while (result.next()) {
                entries.add(new TimelineEntry(
                        result.getLong("start_offset_ms"),
                        result.getLong("end_offset_ms"),
                        TimelineSource.TRANSCRIPT,
                        "转录",
                        "主播",
                        result.getString("text")));
            }
        }
        return List.copyOf(entries);
    }

    private static TranscriptionStatus readTranscriptionStatus(Connection connection)
            throws SQLException {
        if (!hasTable(connection, "transcription_meta")) {
            return TranscriptionStatus.empty();
        }
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT model, language, updated_at,
                            (SELECT COUNT(*) FROM transcript) AS segments
                     FROM transcription_meta
                     WHERE id = 1
                     """)) {
            if (!result.next()) {
                return TranscriptionStatus.empty();
            }
            return new TranscriptionStatus(
                    result.getString("model"),
                    result.getString("language"),
                    result.getInt("segments"),
                    Instant.parse(result.getString("updated_at")));
        }
    }

    private static boolean hasTable(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static GuardPurchaseKind nullablePurchaseKind(String value) {
        return value == null || value.isBlank() ? null : GuardPurchaseKind.valueOf(value);
    }

    private static String label(EventKind kind) {
        return switch (kind) {
            case LIVE -> "开播";
            case PREPARING -> "下播";
            case DANMAKU -> "弹幕";
            case ROOM_CHANGE -> "房间";
            case GIFT -> "礼物";
            case SUPER_CHAT -> "醒目留言";
            case GUARD -> "大航海";
        };
    }

    private static String content(NormalizedEvent event) {
        String summary = event.summary();
        String username = event.username();
        if (username == null || username.isBlank() || !summary.startsWith(username)) {
            return summary;
        }
        return summary.substring(username.length()).replaceFirst("^[：: ]+", "");
    }
}
