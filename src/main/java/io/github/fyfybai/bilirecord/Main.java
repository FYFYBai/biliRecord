package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !"--watch".equals(args[1]))) {
            printUsage();
            System.exit(2);
        }

        try {
            long roomId = RoomIdParser.parse(args[0]);
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
            System.err.println("Room status request failed: " + exception.getMessage());
            System.exit(1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void watch(BiliHttpClient client, long roomId) throws IOException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            RoomInfo room = client.getRoomInfo(roomId);
            System.out.printf("%s room=%d status=%s title=%s%n",
                    Instant.now(), room.roomId(), room.status(), room.title());
            Thread.sleep(Duration.ofSeconds(ThreadLocalRandom.current().nextInt(25, 36)));
        }
    }

    private static void printUsage() {
        System.err.println("Usage: java -jar bili-record.jar <room-id-or-url> [--watch]");
    }
}
