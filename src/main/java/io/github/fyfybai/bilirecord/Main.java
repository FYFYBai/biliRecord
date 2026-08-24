package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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

        if (args.length < 1 || args.length > 2
                || (args.length == 2 && !"--watch".equals(args[1]) && !"--streams".equals(args[1]))) {
            printUsage();
            System.exit(2);
        }

        try {
            long roomId = RoomIdParser.parse(args[0]);
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

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java -jar bili-record.jar --login");
        System.err.println("  java -jar bili-record.jar <room-id-or-url> [--watch|--streams]");
    }
}
