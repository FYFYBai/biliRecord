package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        if (args.length == 1 && "--login".equals(args[0])) {
            AuthLoginWindow.open(new AuthManager());
            return;
        }

        if (args.length < 1 || args.length > 3
                || (args.length == 2 && !"--watch".equals(args[1]) && !"--streams".equals(args[1]))
                || (args.length == 3 && !"--record".equals(args[1]) && !"--danmaku".equals(args[1]))) {
            printUsage();
            System.exit(2);
        }

        try {
            long roomId = RoomIdParser.parse(args[0]);
            if (args.length == 3) {
                Duration duration = parseDuration(args[2]);
                if ("--record".equals(args[1])) {
                    record(roomId, duration);
                } else {
                    listenDanmaku(roomId, duration);
                }
                return;
            }
            if (args.length == 2 && "--streams".equals(args[1])) {
                printStreams(new StreamResolver().resolve(roomId));
                return;
            }
            BiliHttpClient client = new BiliHttpClient();
            if (args.length == 1) {
                System.out.println(client.getRoomInfo(roomId).status());
                return;
            }
            watch(client, roomId);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            printUsage();
            System.exit(2);
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        } catch (IOException exception) {
            System.err.println("Bilibili request failed: " + exception.getMessage());
            System.exit(1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void watch(BiliHttpClient client, long roomId) throws IOException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            RoomInfo room = client.getRoomInfo(roomId);
            System.out.printf("%s room=%d uid=%d status=%s title=%s%n",
                    Instant.now(), room.roomId(), room.uid(), room.status(), room.title());
            Thread.sleep(Duration.ofSeconds(ThreadLocalRandom.current().nextInt(25, 36)));
        }
    }

    private static void printStreams(PlayInfo playInfo) {
        System.out.printf("room=%d uid=%d status=%s%n",
                playInfo.roomId(), playInfo.uid(), playInfo.status());
        System.out.println("qualities:");
        for (QualityInfo quality : playInfo.qualities()) {
            String resolution = quality.resolutionLabel().isBlank()
                    ? ""
                    : " resolution=" + quality.resolutionLabel();
            System.out.printf("  qn=%d name=%s%s%n", quality.qn(), quality.name(), resolution);
        }
        System.out.println("streams:");
        for (StreamVariant stream : playInfo.streams()) {
            String resolution = stream.width() > 0 && stream.height() > 0
                    ? stream.width() + "x" + stream.height()
                    : "unknown";
            System.out.printf("  protocol=%s format=%s codec=%s qn=%d resolution=%s accepts=%s%n",
                    stream.protocol(), stream.format(), stream.codec(), stream.qualityNumber(),
                    resolution, stream.acceptedQualityNumbers());
            for (var url : stream.urls()) {
                System.out.printf("    cdn=%s%n", url.getHost());
            }
        }
    }

    private static void record(long roomId, Duration duration) throws IOException, InterruptedException {
        PlayInfo playInfo = new StreamResolver().resolve(roomId);
        if (playInfo.status() != RoomStatus.LIVE) {
            throw new IllegalStateException("Room " + roomId + " is offline");
        }

        StreamVariant selected = new StreamSelector().selectPrefer1080p(playInfo);
        var streamUrl = selected.urls().getFirst();
        MediaInfo media = new MediaProbe().probe(streamUrl, playInfo.roomId());
        System.out.printf("selected protocol=%s format=%s codec=%s qn=%d probed=%dx%d/%s%n",
                selected.protocol(), selected.format(), selected.codec(), selected.qualityNumber(),
                media.width(), media.height(), media.codec());
        Path output = new RecorderManager().record(streamUrl, playInfo.roomId(), duration);
        System.out.println("saved=" + output);
    }

    private static Duration parseDuration(String value) {
        try {
            long seconds = Long.parseLong(value);
            if (seconds > 0) {
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException ignored) {
            // Replaced below with a user-facing validation error.
        }
        throw new IllegalArgumentException("Recording duration must be a positive number of seconds");
    }

    private static void listenDanmaku(long roomId, Duration duration)
            throws IOException, InterruptedException {
        DanmakuInfo info = new DanmakuInfoResolver().resolve(roomId);
        try (DanmakuClient client = new DanmakuClient()) {
            client.connect(info, message -> System.out.printf("[%s(%d)] %s%n",
                    message.username(), message.uid(), message.text()));
            System.out.printf("connected room=%d uid=%d server=%s%n",
                    info.roomId(), info.uid(), info.servers().getFirst().getHost());
            client.listen(duration);
        }
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -jar bili-record.jar --login");
        System.err.println("  java -jar bili-record.jar <room-id-or-url> [--watch|--streams]");
        System.err.println("  java -jar bili-record.jar <room-id-or-url> --record <seconds>");
        System.err.println("  java -jar bili-record.jar <room-id-or-url> --danmaku <seconds>");
    }
}
