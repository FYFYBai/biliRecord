package io.github.fyfybai.bilirecord;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AutoRecorder {
    private static final Duration ACTIVE_POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Logger LOG = AppLog.get(AutoRecorder.class);

    private final BiliHttpClient roomClient;
    private final Path recordingsDirectory;

    public AutoRecorder() {
        this(new BiliHttpClient(), Path.of("recordings"));
    }

    public AutoRecorder(Path recordingsDirectory) {
        this(new BiliHttpClient(), recordingsDirectory);
    }

    AutoRecorder(BiliHttpClient roomClient) {
        this(roomClient, Path.of("recordings"));
    }

    AutoRecorder(BiliHttpClient roomClient, Path recordingsDirectory) {
        this.roomClient = roomClient;
        this.recordingsDirectory = recordingsDirectory.toAbsolutePath().normalize();
    }

    public void run(long roomId) throws IOException, InterruptedException {
        run(roomId, RecordingObserver.NONE);
    }

    public void run(long roomId, RecordingObserver observer) throws IOException, InterruptedException {
        LifecycleStateMachine lifecycle = new LifecycleStateMachine();
        RetryBackoff apiBackoff = new RetryBackoff();
        RetryBackoff recoveryBackoff = new RetryBackoff();
        AtomicReference<LiveRecordingSession> currentSession = new AtomicReference<>();
        Thread shutdownHook = new Thread(() -> closeForShutdown(currentSession.get()), "recorder-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                RoomInfo room;
                try {
                    room = roomClient.getRoomInfo(roomId);
                    apiBackoff.reset();
                } catch (IOException exception) {
                    waitForRetry("room API", exception, apiBackoff, observer);
                    continue;
                }
                LifecycleAction action = lifecycle.observe(room.status());
                observer.onRoomUpdated(room, lifecycle.state());
                LOG.fine(() -> "%s room=%d state=%s title=%s".formatted(
                        Instant.now(), room.roomId(), lifecycle.state(), room.title()));
                if (action == LifecycleAction.START) {
                    try {
                        LiveRecordingSession session = LiveRecordingSession.start(
                                room, recordingsDirectory, observer);
                        currentSession.set(session);
                        lifecycle.recordingStarted();
                        recoveryBackoff.reset();
                        LOG.info(() -> "Recording started: " + session.directory());
                        observer.onRecordingStarted(room, session.directory());
                    } catch (IOException exception) {
                        lifecycle.startFailed();
                        waitForRetry("recording start", exception, recoveryBackoff, observer);
                        continue;
                    }
                } else if (action == LifecycleAction.STOP) {
                    LiveRecordingSession session = currentSession.getAndSet(null);
                    if (session != null) {
                        session.close();
                    }
                    lifecycle.recordingStopped();
                    LOG.info("Recording stopped after three offline confirmations");
                    observer.onRecordingStopped(room);
                } else if (room.status() == RoomStatus.LIVE) {
                    LiveRecordingSession session = currentSession.get();
                    if (session != null) {
                        try {
                            String recovery = session.recoverIfNeeded();
                            if (recovery != null) {
                                observer.onRecovery(recovery);
                            }
                            recoveryBackoff.reset();
                        } catch (IOException exception) {
                            waitForRetry("recording recovery", exception, recoveryBackoff, observer);
                            continue;
                        }
                    }
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

    private static void waitForRetry(
            String operation,
            IOException exception,
            RetryBackoff backoff,
            RecordingObserver observer)
            throws InterruptedException {
        Duration delay = backoff.nextDelay();
        String message = "%s failed: %s; retrying in %d seconds".formatted(
                operation, exception.getMessage(), delay.toSeconds());
        LOG.warning(message);
        LOG.log(Level.FINE, message + " details", exception);
        observer.onWarning(operation, message);
        Thread.sleep(delay);
    }

    private static void closeForShutdown(LiveRecordingSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (IOException exception) {
            LOG.log(Level.SEVERE, "Could not close recording session", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
