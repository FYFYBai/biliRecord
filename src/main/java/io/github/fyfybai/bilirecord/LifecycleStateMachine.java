package io.github.fyfybai.bilirecord;

final class LifecycleStateMachine {
    private static final int OFFLINE_CONFIRMATIONS = 3;

    private LifecycleState state = LifecycleState.OFFLINE;
    private int offlineCount;

    LifecycleAction observe(RoomStatus roomStatus) {
        if (state == LifecycleState.OFFLINE && roomStatus == RoomStatus.LIVE) {
            state = LifecycleState.STARTING;
            return LifecycleAction.START;
        }
        if ((state == LifecycleState.RECORDING || state == LifecycleState.VERIFYING_END)
                && roomStatus == RoomStatus.LIVE) {
            state = LifecycleState.RECORDING;
            offlineCount = 0;
            return LifecycleAction.NONE;
        }
        if (state == LifecycleState.RECORDING && roomStatus == RoomStatus.OFFLINE) {
            state = LifecycleState.VERIFYING_END;
            offlineCount = 1;
            return LifecycleAction.NONE;
        }
        if (state == LifecycleState.VERIFYING_END && roomStatus == RoomStatus.OFFLINE
                && ++offlineCount >= OFFLINE_CONFIRMATIONS) {
            state = LifecycleState.STOPPING;
            return LifecycleAction.STOP;
        }
        return LifecycleAction.NONE;
    }

    void recordingStarted() {
        requireState(LifecycleState.STARTING);
        state = LifecycleState.RECORDING;
    }

    void startFailed() {
        requireState(LifecycleState.STARTING);
        state = LifecycleState.OFFLINE;
    }

    void recordingStopped() {
        requireState(LifecycleState.STOPPING);
        state = LifecycleState.OFFLINE;
        offlineCount = 0;
    }

    LifecycleState state() {
        return state;
    }

    private void requireState(LifecycleState expected) {
        if (state != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + state);
        }
    }
}
