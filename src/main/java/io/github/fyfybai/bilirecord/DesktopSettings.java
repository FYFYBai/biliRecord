package io.github.fyfybai.bilirecord;

public record DesktopSettings(String room) {
    public DesktopSettings {
        room = room == null ? "" : room;
    }
}
