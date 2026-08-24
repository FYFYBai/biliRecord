package io.github.fyfybai.bilirecord;

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

final class SessionPlayerPanel extends JPanel implements AutoCloseable {
    private static final int CONTROL_HEIGHT = 92;
    private static final long SEEK_STEP_MS = 5_000;

    private final SessionTimeline timeline;
    private final EmbeddedMediaPlayerComponent playerComponent;
    private final JLayeredPane videoLayer = new VideoLayer();
    private final TimelineSlider slider = new TimelineSlider();
    private final JButton playButton = playerButton(">", "播放 / 暂停");
    private final JButton muteButton = playerButton("V", "静音");
    private final JButton rateButton = playerButton("1x", "播放速度");
    private final JButton fullscreenButton = playerButton("[ ]", "全屏");
    private final JSlider volumeSlider = new JSlider(0, 100, 80);
    private final JLabel timeLabel = playerLabel("00:00 / 00:00");
    private final JLabel segmentLabel = playerLabel("分段 --/--");
    private final JLabel noticeLabel = playerLabel(" ");
    private final JPanel controls;
    private final Timer overlayTimer;
    private Consumer<Long> positionListener = ignored -> { };

    private SessionSegment currentSegment;
    private long pendingSeekMs = -1;
    private long currentOffsetMs;
    private float playbackRate = 1f;
    private boolean sliderChanging;
    private boolean updatingSlider;
    private boolean controlHover;
    private boolean closed;
    private JFrame fullscreenFrame;
    private JSplitPane ownerSplit;
    private Component splitPlaceholder;
    private int ownerDividerLocation;

    SessionPlayerPanel(SessionTimeline timeline) {
        super(new java.awt.BorderLayout());
        this.timeline = timeline;
        discoverVlc();
        playerComponent = new EmbeddedMediaPlayerComponent();
        playerComponent.setBackground(Color.BLACK);
        playerComponent.mediaPlayer().events().addMediaPlayerEventListener(new PlayerEvents());
        controls = buildControls();
        overlayTimer = new Timer(1_800, event -> hideControls());
        overlayTimer.setRepeats(false);

        setBackground(Color.BLACK);
        setMinimumSize(new Dimension(480, 320));
        videoLayer.setBackground(Color.BLACK);
        videoLayer.setOpaque(true);
        videoLayer.add(playerComponent, JLayeredPane.DEFAULT_LAYER);
        videoLayer.add(controls, JLayeredPane.PALETTE_LAYER);
        add(videoLayer, java.awt.BorderLayout.CENTER);

        updatingSlider = true;
        slider.setTimeline(timeline.entries(), timeline.durationMs());
        updatingSlider = false;
        timeLabel.setText(formatTime(0) + " / " + formatTime(timeline.durationMs()));
        configureMouseBehavior();
        configureKeyboardControls();
    }

    private JPanel buildControls() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(0x151515));
        panel.setBorder(BorderFactory.createEmptyBorder(2, 12, 7, 12));

        slider.setOpaque(false);
        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        slider.setPreferredSize(new Dimension(10, 28));
        slider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        slider.putClientProperty("FlatLaf.style",
                "trackColor: #747474; thumbColor: #FB7299; focusWidth: 0; trackWidth: 3");
        slider.addChangeListener(event -> {
            if (updatingSlider) {
                return;
            }
            sliderChanging = slider.getValueIsAdjusting();
            if (!sliderChanging) {
                seekTo(slider.getValue());
            }
        });
        slider.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    seekFromSliderPoint(event.getX());
                }
            }
        });
        panel.add(slider);

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        playButton.addActionListener(event -> togglePlayback());
        muteButton.addActionListener(event -> toggleMute());
        rateButton.addActionListener(event -> showRateMenu());
        fullscreenButton.addActionListener(event -> toggleFullscreen());
        row.add(playButton);
        row.add(Box.createHorizontalStrut(4));
        row.add(timeLabel);
        row.add(Box.createHorizontalGlue());
        row.add(segmentLabel);
        row.add(Box.createHorizontalStrut(10));
        row.add(rateButton);
        row.add(muteButton);

        volumeSlider.setOpaque(false);
        volumeSlider.setPreferredSize(new Dimension(82, 24));
        volumeSlider.setMaximumSize(new Dimension(82, 24));
        volumeSlider.setToolTipText("音量");
        volumeSlider.putClientProperty("FlatLaf.style",
                "trackColor: #747474; thumbColor: #FFFFFF; focusWidth: 0; trackWidth: 3");
        volumeSlider.addChangeListener(event -> {
            int volume = volumeSlider.getValue();
            playerComponent.mediaPlayer().audio().setVolume(volume);
            if (volume > 0 && playerComponent.mediaPlayer().audio().isMute()) {
                playerComponent.mediaPlayer().audio().setMute(false);
            }
            updateMuteButton();
        });
        playerComponent.mediaPlayer().audio().setVolume(volumeSlider.getValue());
        row.add(volumeSlider);
        row.add(fullscreenButton);
        panel.add(row);

        noticeLabel.setForeground(new Color(0xFFD06A));
        noticeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        noticeLabel.setPreferredSize(new Dimension(10, 18));
        panel.add(noticeLabel);
        panel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                controlHover = true;
                showControls();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                controlHover = false;
                scheduleControlHide();
            }
        });
        return panel;
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
        long requested = Math.max(0, Math.min(timeline.durationMs(), requestedOffsetMs));
        SessionSegment segment = timeline.segmentAt(requested)
                .or(() -> timeline.nextSegment(requested))
                .orElse(timeline.segments().get(timeline.segments().size() - 1));
        long offsetMs = Math.max(segment.startedOffsetMs(),
                Math.min(requested, segment.endedOffsetMs()));
        noticeLabel.setText(offsetMs == requested
                ? " "
                : "该时间点没有录像，已跳到相邻分段");
        showControls();
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

    private void toggleMute() {
        playerComponent.mediaPlayer().audio().setMute(
                !playerComponent.mediaPlayer().audio().isMute());
        updateMuteButton();
        showControls();
    }

    private void updateMuteButton() {
        boolean muted = playerComponent.mediaPlayer().audio().isMute()
                || volumeSlider.getValue() == 0;
        muteButton.setText(muted ? "V-" : "V");
        muteButton.setToolTipText(muted ? "取消静音" : "静音");
    }

    private void showRateMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (float rate : new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f}) {
            JMenuItem item = new JMenuItem(formatRate(rate));
            item.addActionListener(event -> setPlaybackRate(rate));
            menu.add(item);
        }
        menu.show(rateButton, 0, -menu.getPreferredSize().height);
    }

    private void setPlaybackRate(float rate) {
        playbackRate = rate;
        playerComponent.mediaPlayer().controls().setRate(rate);
        rateButton.setText(formatRate(rate));
        showControls();
    }

    private void playSegment(SessionSegment segment, long localOffsetMs) {
        if (!Files.isRegularFile(segment.path())) {
            noticeLabel.setText("录像分段不存在：" + segment.path().getFileName());
            return;
        }
        currentSegment = segment;
        pendingSeekMs = Math.max(0, localOffsetMs);
        updateSegmentLabel();
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
            playButton.setText(">");
            showControls();
        }
    }

    private void updateSegmentLabel() {
        int index = timeline.segments().indexOf(currentSegment);
        segmentLabel.setText(index < 0
                ? "分段 --/--"
                : "分段 %d/%d".formatted(index + 1, timeline.segments().size()));
    }

    private void updatePosition(long localTimeMs) {
        currentOffsetMs = currentSegment == null
                ? 0
                : Math.min(timeline.durationMs(), currentSegment.startedOffsetMs() + localTimeMs);
        if (!sliderChanging) {
            updatingSlider = true;
            slider.setValue((int) Math.min(Integer.MAX_VALUE, currentOffsetMs));
            updatingSlider = false;
        }
        timeLabel.setText(formatTime(currentOffsetMs) + " / " + formatTime(timeline.durationMs()));
        positionListener.accept(currentOffsetMs);
    }

    private void seekFromSliderPoint(int x) {
        int usable = Math.max(1, slider.getWidth() - 16);
        double ratio = Math.max(0, Math.min(1, (x - 8) / (double) usable));
        int value = (int) Math.round(ratio * slider.getMaximum());
        updatingSlider = true;
        slider.setValue(value);
        updatingSlider = false;
        seekTo(value);
    }

    private void configureMouseBehavior() {
        MouseMotionAdapter motion = new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                showControls();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                showControls();
            }
        };
        MouseAdapter clicks = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    toggleFullscreen();
                } else if (event.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(event)) {
                    togglePlayback();
                }
            }
        };
        Component surface = playerComponent.videoSurfaceComponent();
        surface.addMouseMotionListener(motion);
        surface.addMouseListener(clicks);
        videoLayer.addMouseMotionListener(motion);
    }

    private void configureKeyboardControls() {
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "playPause", this::togglePlayback);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "backward",
                () -> seekTo(currentOffsetMs - SEEK_STEP_MS));
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "forward",
                () -> seekTo(currentOffsetMs + SEEK_STEP_MS));
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "volumeUp",
                () -> volumeSlider.setValue(Math.min(100, volumeSlider.getValue() + 5)));
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "volumeDown",
                () -> volumeSlider.setValue(Math.max(0, volumeSlider.getValue() - 5)));
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "mute", this::toggleMute);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F, 0), "fullscreen", this::toggleFullscreen);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exitFullscreen",
                this::exitFullscreen);
    }

    private void bindKey(KeyStroke key, String name, Runnable action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(key, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    private void toggleFullscreen() {
        if (fullscreenFrame == null) {
            enterFullscreen();
        } else {
            exitFullscreen();
        }
    }

    private void enterFullscreen() {
        if (!(getParent() instanceof JSplitPane split)) {
            return;
        }
        ownerSplit = split;
        ownerDividerLocation = split.getDividerLocation();
        splitPlaceholder = new JPanel();
        splitPlaceholder.setBackground(Color.BLACK);
        if (split.getLeftComponent() == this) {
            split.setLeftComponent(splitPlaceholder);
        } else {
            split.setRightComponent(splitPlaceholder);
        }

        JFrame window = new JFrame();
        window.setUndecorated(true);
        window.setBackground(Color.BLACK);
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.add(this);
        fullscreenFrame = window;
        GraphicsDevice device = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice();
        device.setFullScreenWindow(window);
        showControls();
        requestFocusInWindow();
    }

    private void exitFullscreen() {
        JFrame window = fullscreenFrame;
        JSplitPane split = ownerSplit;
        if (window == null || split == null) {
            return;
        }
        GraphicsDevice device = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice();
        device.setFullScreenWindow(null);
        window.remove(this);
        window.dispose();
        fullscreenFrame = null;
        if (split.getLeftComponent() == splitPlaceholder) {
            split.setLeftComponent(this);
        } else {
            split.setRightComponent(this);
        }
        split.setDividerLocation(ownerDividerLocation);
        ownerSplit = null;
        splitPlaceholder = null;
        showControls();
    }

    private void showControls() {
        controls.setVisible(true);
        scheduleControlHide();
    }

    private void scheduleControlHide() {
        overlayTimer.stop();
        if (!controlHover && playerComponent.mediaPlayer().status().isPlaying()) {
            overlayTimer.restart();
        }
    }

    private void hideControls() {
        if (!controlHover && !sliderChanging && playerComponent.mediaPlayer().status().isPlaying()) {
            controls.setVisible(false);
        }
    }

    @Override
    public void close() {
        closed = true;
        overlayTimer.stop();
        exitFullscreen();
        playerComponent.release();
    }

    private static JButton playerButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setForeground(Color.WHITE);
        button.setFont(button.getFont().deriveFont(java.awt.Font.BOLD, 15f));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setPreferredSize(new Dimension(40, 32));
        button.setMaximumSize(new Dimension(54, 32));
        button.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("FlatLaf.style",
                "foreground: #FFFFFF; hoverForeground: #FB7299; focusWidth: 0");
        return button;
    }

    private static JLabel playerLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(12f));
        return label;
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
        if (seconds < 3_600) {
            return "%02d:%02d".formatted(seconds / 60, seconds % 60);
        }
        return "%02d:%02d:%02d".formatted(
                seconds / 3_600,
                seconds / 60 % 60,
                seconds % 60);
    }

    private static String formatRate(float rate) {
        return rate == Math.round(rate)
                ? "%dx".formatted(Math.round(rate))
                : "%sx".formatted(rate);
    }

    private final class VideoLayer extends JLayeredPane {
        @Override
        public void doLayout() {
            int width = getWidth();
            int height = getHeight();
            playerComponent.setBounds(0, 0, width, height);
            controls.setBounds(0, Math.max(0, height - CONTROL_HEIGHT), width, CONTROL_HEIGHT);
        }
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
                playerComponent.mediaPlayer().controls().setRate(playbackRate);
                playButton.setText("||");
                scheduleControlHide();
            });
        }

        @Override
        public void paused(MediaPlayer mediaPlayer) {
            SwingUtilities.invokeLater(() -> {
                playButton.setText(">");
                showControls();
            });
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
                playButton.setText(">");
                showControls();
                AppLog.get(SessionPlayerPanel.class).warning("VLC playback failed");
            });
        }
    }
}
