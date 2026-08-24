package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class AppLog {
    private static final Path DIRECTORY = Path.of("logs");
    private static final Logger ROOT = Logger.getLogger("io.github.fyfybai.bilirecord");
    private static final CopyOnWriteArrayList<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ArrayDeque<String> RECENT = new ArrayDeque<>();
    private static final int RECENT_LIMIT = 500;
    private static final Formatter FORMATTER = new LineFormatter();

    static {
        configure();
    }

    private AppLog() {
    }

    public static Logger get(Class<?> type) {
        return Logger.getLogger(type.getName());
    }

    public static List<String> recentLines() {
        synchronized (RECENT) {
            return List.copyOf(RECENT);
        }
    }

    public static AutoCloseable subscribe(Consumer<String> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    public static Path directory() {
        return DIRECTORY.toAbsolutePath().normalize();
    }

    private static void configure() {
        ROOT.setUseParentHandlers(false);
        ROOT.setLevel(Level.ALL);

        ConsoleHandler console = new ConsoleHandler();
        console.setLevel(Level.INFO);
        console.setFormatter(FORMATTER);
        try {
            console.setEncoding(StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException exception) {
            throw new AssertionError(exception);
        }
        ROOT.addHandler(console);

        Handler ui = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (!isLoggable(record)) {
                    return;
                }
                String line = FORMATTER.format(record);
                synchronized (RECENT) {
                    RECENT.addLast(line);
                    while (RECENT.size() > RECENT_LIMIT) {
                        RECENT.removeFirst();
                    }
                }
                for (Consumer<String> listener : LISTENERS) {
                    try {
                        listener.accept(line);
                    } catch (RuntimeException ignored) {
                        // A UI listener must never interrupt the operation being logged.
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        ui.setLevel(Level.INFO);
        ROOT.addHandler(ui);

        try {
            Files.createDirectories(DIRECTORY);
            FileHandler file = new FileHandler(
                    DIRECTORY.resolve("bilirecord-%g.log").toString(),
                    10 * 1024 * 1024,
                    5,
                    true);
            file.setLevel(Level.ALL);
            file.setFormatter(FORMATTER);
            file.setEncoding(StandardCharsets.UTF_8.name());
            ROOT.addHandler(file);
        } catch (IOException exception) {
            ROOT.log(Level.WARNING, "File logging is unavailable", exception);
        }
    }

    private static final class LineFormatter extends Formatter {
        private static final DateTimeFormatter TIME = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault());

        @Override
        public String format(LogRecord record) {
            String source = record.getSourceClassName();
            if (source == null) {
                source = record.getLoggerName();
            } else {
                source = source.substring(source.lastIndexOf('.') + 1);
            }
            StringBuilder line = new StringBuilder()
                    .append(TIME.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(' ')
                    .append(record.getLevel().getName())
                    .append(" [")
                    .append(source)
                    .append("] ")
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                line.append(trace);
            }
            return line.toString();
        }
    }
}
