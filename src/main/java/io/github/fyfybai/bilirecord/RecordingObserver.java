package io.github.fyfybai.bilirecord;

import java.nio.file.Path;

public interface RecordingObserver {
    RecordingObserver NONE = new RecordingObserver() {
    };

    default void onRoomUpdated(RoomInfo room, LifecycleState state) {
    }

    default void onRecordingStarted(RoomInfo room, Path sessionDirectory) {
    }

    default void onRecordingStopped(RoomInfo room) {
    }

    default void onRecovery(String message) {
    }

    default void onEvent(DanmakuEvent event, long sessionOffsetMs) {
    }

    default void onWarning(String operation, String message) {
    }
}
