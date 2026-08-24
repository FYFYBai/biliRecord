package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

public final class AutoRecorder {
    private static final Duration ACTIVE_POLL_INTERVAL = Duration.ofSeconds(5);

    private final BiliHttpClient roomClient;

    public AutoRecorder() {
        this(new BiliHttpClient());
    }

    AutoRecorder(BiliHttpClient roomClient) {
        this.roomClient = roomClient;
    }

    public void run(long roomId) throws IOException, InterruptedException {
        LifecycleStateMachine lifecycle = new LifecycleStateMachine();
        AtomicReference<LiveRecordingSession> currentSession = new AtomicReference<>();
        Thread shutdownHook = new Thread(() -> closeForShutdown(currentSession.get()), "recorder-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                RoomInfo room = roomClient.getRoomInfo(roomId);
                LifecycleAction action = lifecycle.observe(room.status());
                System.out.printf("%s room=%d state=%s title=%s%n",
                        Instant.now(), room.roomId(), lifecycle.state(), room.title());
                if (action == LifecycleAction.START) {
                    try {
                        LiveRecordingSession session = LiveRecordingSession.start(room);
                        currentSession.set(session);
                        lifecycle.recordingStarted();
                        System.out.println("recording session=" + session.directory());
                    } catch (IOException | InterruptedException | RuntimeException exception) {
                        lifecycle.startFailed();
                        throw exception;
                    }
                } else if (action == LifecycleAction.STOP) {
                    LiveRecordingSession session = currentSession.getAndSet(null);
                    if (session != null) {
                        session.close();
                    }
                    lifecycle.recordingStopped();
                    System.out.println("recording stopped after three offline confirmations");
                }
                Thread.sleep(pollInterval(lifecycle.state()));
            }
        } finally {
            LiveRecordingSession session = currentSession.getAndSet(null);
            if (session != null) {
                session.close();
            }
            if (Thread.currentThread() != shutdownHook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // The hook is already running during JVM shutdown.
                }
            }
        }
    }

    private static Duration pollInterval(LifecycleState state) {
        if (state != LifecycleState.OFFLINE) {
            return ACTIVE_POLL_INTERVAL;
        }
        return Duration.ofSeconds(ThreadLocalRandom.current().nextInt(25, 36));
    }

    private static void closeForShutdown(LiveRecordingSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (IOException exception) {
            System.err.println("Could not close recording session: " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
