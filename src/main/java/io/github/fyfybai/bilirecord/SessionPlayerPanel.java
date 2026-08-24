package io.github.fyfybai.bilirecord;

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

final class SessionPlayerPanel extends JPanel implements AutoCloseable {
    private final SessionTimeline timeline;
    private final EmbeddedMediaPlayerComponent playerComponent;
    private final TimelineSlider slider = new TimelineSlider();
    private final JButton playButton = new JButton("播放");
    private final JButton stopButton = new JButton("停止");
    private final JButton muteButton = new JButton("静音");
    private final JSlider volumeSlider = new JSlider(0, 100, 80);
    private final JLabel timeLabel = new JLabel("00:00:00 / 00:00:00");
    private final JLabel noticeLabel = new JLabel(" ");
    private Consumer<Long> positionListener = ignored -> { };

    private SessionSegment currentSegment;
    private long pendingSeekMs = -1;
    private boolean sliderChanging;
    private boolean updatingSlider;
    private boolean closed;

    SessionPlayerPanel(SessionTimeline timeline) {
        super(new BorderLayout());
        this.timeline = timeline;
        discoverVlc();
        playerComponent = new EmbeddedMediaPlayerComponent();
        playerComponent.setBackground(Color.BLACK);
        playerComponent.mediaPlayer().events().addMediaPlayerEventListener(new PlayerEvents());

        setBackground(Color.BLACK);
        add(playerComponent, BorderLayout.CENTER);
        add(buildControls(), BorderLayout.SOUTH);
        updatingSlider = true;
        slider.setTimeline(timeline.entries(), timeline.durationMs());
        updatingSlider = false;
        timeLabel.setText(formatTime(0) + " / " + formatTime(timeline.durationMs()));
    }

    private JPanel buildControls() {
        JPanel controls = new JPanel(new BorderLayout(10, 4));
        controls.setBackground(UiTheme.SURFACE);
        controls.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        UiTheme.accent(playButton);
        UiTheme.outline(stopButton);
        UiTheme.outline(muteButton);
        playButton.addActionListener(event -> togglePlayback());
        stopButton.addActionListener(event -> stop());
        muteButton.addActionListener(event -> toggleMute());
        volumeSlider.setPreferredSize(new java.awt.Dimension(90, 24));
        volumeSlider.setToolTipText("音量");
        volumeSlider.addChangeListener(event -> playerComponent.mediaPlayer()
                .audio().setVolume(volumeSlider.getValue()));
        playerComponent.mediaPlayer().audio().setVolume(volumeSlider.getValue());

        JPanel commands = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        commands.setOpaque(false);
        commands.add(playButton);
        commands.add(stopButton);
        commands.add(muteButton);
        commands.add(volumeSlider);
        commands.add(timeLabel);
        controls.add(commands, BorderLayout.WEST);

        slider.addChangeListener(event -> {
            if (updatingSlider) {
                return;
            }
            sliderChanging = slider.getValueIsAdjusting();
            if (!sliderChanging) {
                seekTo(slider.getValue());
            }
        });
        controls.add(slider, BorderLayout.CENTER);
        noticeLabel.setForeground(UiTheme.WARNING);
        controls.add(noticeLabel, BorderLayout.SOUTH);
        return controls;
    }

    void setPositionListener(Consumer<Long> listener) {
        positionListener = listener == null ? ignored -> { } : listener;
    }

    void setMarkers(List<TimelineEntry> entries) {
        updatingSlider = true;
        slider.setTimeline(entries, timeline.durationMs());
        updatingSlider = false;
    }

    void seekTo(long requestedOffsetMs) {
        if (closed || timeline.segments().isEmpty()) {
            return;
        }
        SessionSegment segment = timeline.segmentAt(requestedOffsetMs)
                .or(() -> timeline.nextSegment(requestedOffsetMs))
                .orElse(timeline.segments().get(timeline.segments().size() - 1));
        long offsetMs = Math.max(segment.startedOffsetMs(),
                Math.min(requestedOffsetMs, segment.endedOffsetMs()));
        if (offsetMs != requestedOffsetMs) {
            noticeLabel.setText("该时间点没有录像，已跳到相邻分段");
        } else {
            noticeLabel.setText(" ");
        }
        playSegment(segment, offsetMs - segment.startedOffsetMs());
    }

    private void togglePlayback() {
        if (playerComponent.mediaPlayer().status().isPlaying()) {
            playerComponent.mediaPlayer().controls().pause();
            return;
        }
        if (currentSegment == null) {
            seekTo(slider.getValue());
        } else {
            playerComponent.mediaPlayer().controls().play();
        }
    }

    private void stop() {
        playerComponent.mediaPlayer().controls().stop();
        currentSegment = null;
        updatePosition(0);
        playButton.setText("播放");
    }

    private void toggleMute() {
        boolean muted = !playerComponent.mediaPlayer().audio().isMute();
        playerComponent.mediaPlayer().audio().setMute(muted);
        muteButton.setText(muted ? "取消静音" : "静音");
    }

    private void playSegment(SessionSegment segment, long localOffsetMs) {
        if (!Files.isRegularFile(segment.path())) {
            noticeLabel.setText("录像分段不存在：" + segment.path().getFileName());
            return;
        }
        currentSegment = segment;
        pendingSeekMs = Math.max(0, localOffsetMs);
        boolean started = playerComponent.mediaPlayer().media().play(segment.path().toString());
        if (!started) {
            noticeLabel.setText("VLC 无法打开该录像分段");
        }
    }

    private void playNextSegment() {
        if (closed || currentSegment == null) {
            return;
        }
        List<SessionSegment> segments = timeline.segments();
        int index = segments.indexOf(currentSegment);
        if (index >= 0 && index + 1 < segments.size()) {
            playSegment(segments.get(index + 1), 0);
        } else {
            playButton.setText("播放");
        }
    }

    private void updatePosition(long localTimeMs) {
        long global = currentSegment == null
                ? 0
                : Math.min(timeline.durationMs(), currentSegment.startedOffsetMs() + localTimeMs);
        if (!sliderChanging) {
            updatingSlider = true;
            slider.setValue((int) Math.min(Integer.MAX_VALUE, global));
            updatingSlider = false;
        }
        timeLabel.setText(formatTime(global) + " / " + formatTime(timeline.durationMs()));
        positionListener.accept(global);
    }

    @Override
    public void close() {
        closed = true;
        playerComponent.release();
    }

    private static void discoverVlc() {
        Path windowsVlc = Path.of("C:\\Program Files\\VideoLAN\\VLC");
        if (Files.isRegularFile(windowsVlc.resolve("libvlc.dll"))) {
            String existing = System.getProperty("jna.library.path", "");
            if (!existing.contains(windowsVlc.toString())) {
                System.setProperty("jna.library.path",
                        existing.isBlank() ? windowsVlc.toString() : existing + ";" + windowsVlc);
            }
        }
        if (!new NativeDiscovery().discover()) {
            throw new IllegalStateException("未找到 VLC 3.x，请先安装 64 位 VLC");
        }
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0, millis) / 1_000;
        return "%02d:%02d:%02d".formatted(
                seconds / 3_600,
                seconds / 60 % 60,
                seconds % 60);
    }

    private final class PlayerEvents extends MediaPlayerEventAdapter {
        @Override
        public void playing(MediaPlayer mediaPlayer) {
            SwingUtilities.invokeLater(() -> {
                if (pendingSeekMs > 0) {
                    long seek = pendingSeekMs;
                    pendingSeekMs = -1;
                    playerComponent.mediaPlayer().controls().setTime(seek);
                } else {
                    pendingSeekMs = -1;
                }
                playButton.setText("暂停");
            });
        }

        @Override
        public void paused(MediaPlayer mediaPlayer) {
            SwingUtilities.invokeLater(() -> playButton.setText("播放"));
        }

        @Override
        public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
            SwingUtilities.invokeLater(() -> updatePosition(newTime));
        }

        @Override
        public void finished(MediaPlayer mediaPlayer) {
            SwingUtilities.invokeLater(SessionPlayerPanel.this::playNextSegment);
        }

        @Override
        public void error(MediaPlayer mediaPlayer) {
            SwingUtilities.invokeLater(() -> {
                noticeLabel.setText("播放失败，详情见日志");
                playButton.setText("播放");
                AppLog.get(SessionPlayerPanel.class).warning("VLC playback failed");
            });
        }
    }
}
