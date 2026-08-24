package io.github.fyfybai.bilirecord;

public record DesktopSettings(String room, String exportDirectory, String recordingDirectory) {
    public DesktopSettings {
        room = room == null ? "" : room;
        exportDirectory = exportDirectory == null ? "" : exportDirectory;
        recordingDirectory = recordingDirectory == null ? "" : recordingDirectory;
    }

    public DesktopSettings(String room, String exportDirectory) {
        this(room, exportDirectory, "");
    }

    public DesktopSettings(String room) {
        this(room, "", "");
    }
}
