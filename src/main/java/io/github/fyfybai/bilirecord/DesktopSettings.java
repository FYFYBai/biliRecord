package io.github.fyfybai.bilirecord;

public record DesktopSettings(String room, String exportDirectory) {
    public DesktopSettings {
        room = room == null ? "" : room;
        exportDirectory = exportDirectory == null ? "" : exportDirectory;
    }

    public DesktopSettings(String room) {
        this(room, "");
    }
}
