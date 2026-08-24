package io.github.fyfybai.bilirecord;

import java.net.URI;
import java.util.Locale;

public final class RoomIdParser {
    private RoomIdParser() {
    }

    public static long parse(String input) {
        String value = input.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            return positiveRoomId(value);
        }

        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw invalidRoom(input);
        }

        String host = uri.getHost();
        if (host == null || !host.toLowerCase(Locale.ROOT).equals("live.bilibili.com")) {
            throw invalidRoom(input);
        }

        String[] pathParts = uri.getPath().split("/");
        if (pathParts.length < 2 || !pathParts[1].chars().allMatch(Character::isDigit)) {
            throw invalidRoom(input);
        }
        return positiveRoomId(pathParts[1]);
    }

    private static long positiveRoomId(String value) {
        try {
            long roomId = Long.parseLong(value);
            if (roomId > 0) {
                return roomId;
            }
        } catch (NumberFormatException ignored) {
            // Replaced below with a user-facing validation error.
        }
        throw invalidRoom(value);
    }

    private static IllegalArgumentException invalidRoom(String value) {
        return new IllegalArgumentException("Invalid Bilibili room ID or URL: " + value);
    }
}
