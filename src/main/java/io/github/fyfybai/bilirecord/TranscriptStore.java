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
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

final class TranscriptStore {
    private final ObjectMapper objectMapper = new ObjectMapper();

    void replace(
            Path sessionDirectory,
            List<TranscriptSegment> segments,
            String model,
            String language) throws IOException {
        Path database = sessionDirectory.resolve("timeline.sqlite");
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath())) {
            connection.setAutoCommit(false);
            try {
                initialize(connection);
                try (var delete = connection.createStatement()) {
                    delete.executeUpdate("DELETE FROM transcript");
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO transcript(
                            segment_path, start_offset_ms, end_offset_ms, text, language)
                        VALUES (?, ?, ?, ?, ?)
                        """)) {
                    for (TranscriptSegment segment : segments) {
                        insert.setString(1, segment.segmentPath());
                        insert.setLong(2, segment.startOffsetMs());
                        insert.setLong(3, segment.endOffsetMs());
                        insert.setString(4, segment.text());
                        insert.setString(5, segment.language());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (var metadata = connection.prepareStatement("""
                        INSERT INTO transcription_meta(id, model, language, updated_at)
                        VALUES (1, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            model = excluded.model,
                            language = excluded.language,
                            updated_at = excluded.updated_at
                        """)) {
                    metadata.setString(1, model);
                    metadata.setString(2, language);
                    metadata.setString(3, Instant.now().toString());
                    metadata.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IOException("Could not store transcript", exception);
        }
        writeJsonl(sessionDirectory.resolve("transcript.jsonl"), segments);
    }

    private void writeJsonl(Path path, List<TranscriptSegment> segments) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (TranscriptSegment segment : segments) {
                ObjectNode line = objectMapper.createObjectNode();
                line.put("segmentPath", segment.segmentPath());
                line.put("startOffsetMs", segment.startOffsetMs());
                line.put("endOffsetMs", segment.endOffsetMs());
                line.put("text", segment.text());
                line.put("language", segment.language());
                writer.write(objectMapper.writeValueAsString(line));
                writer.newLine();
            }
        }
    }

    private static void initialize(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS transcript (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        segment_path TEXT NOT NULL,
                        start_offset_ms INTEGER NOT NULL,
                        end_offset_ms INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        language TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS transcript_start_idx ON transcript(start_offset_ms)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS transcription_meta (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        model TEXT NOT NULL,
                        language TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
        }
    }
}
