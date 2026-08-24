package io.github.fyfybai.bilirecord;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.bootstrapicons.BootstrapIcons;
import org.kordamp.ikonli.swing.FontIcon;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent;
import uk.co.caprica.vlcj.player.component.callback.ScaledCallbackImagePainter;
import uk.co.caprica.vlcj.player.embedded.fullscreen.adaptive.AdaptiveFullScreenStrategy;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.AlphaComposite;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Window;
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
    private final CallbackMediaPlayerComponent playerComponent;
    private final JLayeredPane videoLayer = new VideoLayer();
    private final TimelineSlider slider = new TimelineSlider();
    private final JButton playButton = iconButton(BootstrapIcons.PLAY_FILL, "播放 / 暂停");
    private final JButton muteButton = iconButton(BootstrapIcons.VOLUME_UP_FILL, "静音");
    private final JButton rateButton = textButton("1x", "播放速度");
    private final JButton fullscreenButton = iconButton(BootstrapIcons.FULLSCREEN, "全屏");
    private final JButton centerPlayButton = iconButton(BootstrapIcons.PLAY_FILL, "播放", 34);
    private final JSlider volumeSlider = new JSlider(0, 100, 80);
    private final JLabel timeLabel = playerLabel("00:00 / 00:00");
    private final JLabel segmentLabel = playerLabel("分段 --/--");
    private final JLabel noticeLabel = playerLabel(" ");
    private final JPanel controls;
    private final JPanel volumePopup;
    private final Timer overlayTimer;
    private final Timer fadeTimer;
    private final Timer volumeHideTimer;
    private final Timer singleClickTimer;
    private final boolean fullscreenSupported;
    private Consumer<Long> positionListener = ignored -> { };

    private SessionSegment currentSegment;
    private long pendingSeekMs = -1;
    private long currentOffsetMs;
    private float playbackRate = 1f;
    private boolean sliderChanging;
    private boolean updatingSlider;
    private boolean controlHover;
    private boolean closed;
    private boolean muted;
    private int lastAudibleVolume = 80;
    private float controlOpacity = 1f;
    private float targetOpacity = 1f;

    SessionPlayerPanel(SessionTimeline timeline) {
        this(timeline, null, ignored -> { });
    }

    SessionPlayerPanel(
            SessionTimeline timeline,
            Window fullscreenWindow,
            Consumer<Boolean> fullscreenListener) {
        super(new java.awt.BorderLayout());
        this.timeline = timeline;
        fullscreenSupported = fullscreenWindow != null;
        discoverVlc();
        playerComponent = new CallbackMediaPlayerComponent();
        playerComponent.setImagePainter(new ScaledCallbackImagePainter());
        playerComponent.setBackground(Color.BLACK);
        playerComponent.mediaPlayer().events().addMediaPlayerEventListener(new PlayerEvents());
        if (fullscreenWindow != null) {
            playerComponent.mediaPlayer().fullScreen().strategy(
                    new AdaptiveFullScreenStrategy(fullscreenWindow) {
                        @Override
                        protected void onBeforeEnterFullScreen() {
                            fullscreenListener.accept(true);
                            setButtonIcon(fullscreenButton, BootstrapIcons.FULLSCREEN_EXIT);
                        }

                        @Override
                        protected void onAfterExitFullScreen() {
                            fullscreenListener.accept(false);
                            setButtonIcon(fullscreenButton, BootstrapIcons.FULLSCREEN);
                        }
                    });
        } else {
            fullscreenButton.setEnabled(false);
        }
        controls = buildControls();
        volumePopup = buildVolumePopup();
        overlayTimer = new Timer(1_800, event -> hideControls());
        overlayTimer.setRepeats(false);
        fadeTimer = new Timer(16, event -> advanceControlFade());
        volumeHideTimer = new Timer(220, event -> hideVolumePopup());
        volumeHideTimer.setRepeats(false);
        singleClickTimer = new Timer(220, event -> togglePlayback());
        singleClickTimer.setRepeats(false);

        setBackground(Color.BLACK);
        setMinimumSize(new Dimension(480, 320));
        videoLayer.setBackground(Color.BLACK);
        videoLayer.setOpaque(true);
        videoLayer.add(playerComponent, JLayeredPane.DEFAULT_LAYER);
        videoLayer.add(controls, JLayeredPane.PALETTE_LAYER);
        configureCenterPlayButton();
        videoLayer.add(centerPlayButton, JLayeredPane.DRAG_LAYER);
        videoLayer.add(volumePopup, JLayeredPane.MODAL_LAYER);
        add(videoLayer, java.awt.BorderLayout.CENTER);

        updatingSlider = true;
        slider.setTimeline(timeline.entries(), timeline.durationMs());
        updatingSlider = false;
        timeLabel.setText(formatTime(0) + " / " + formatTime(timeline.durationMs()));
        configureMouseBehavior();
        configureKeyboardControls();
    }

    private JPanel buildControls() {
        JPanel panel = new PlayerControlsPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 0, 7, 0));

        slider.setOpaque(false);
        slider.setAlignmentX(Component.LEFT_ALIGNMENT);
        slider.setPreferredSize(new Dimension(10, 28));
        slider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
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
        row.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        playButton.addActionListener(event -> togglePlayback());
        muteButton.addActionListener(event -> toggleMute());
        rateButton.addActionListener(event -> showRateMenu());
        fullscreenButton.addActionListener(event -> toggleFullscreen());
        configureVolumeButtonHover();
        row.add(playButton);
        row.add(Box.createHorizontalStrut(4));
        row.add(timeLabel);
        row.add(Box.createHorizontalGlue());
        row.add(segmentLabel);
        row.add(Box.createHorizontalStrut(10));
        row.add(rateButton);
        row.add(muteButton);
        row.add(fullscreenButton);
        panel.add(row);

        noticeLabel.setForeground(new Color(0xFFD06A));
        noticeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        noticeLabel.setPreferredSize(new Dimension(10, 18));
        noticeLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
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

    private JPanel buildVolumePopup() {
        JPanel popup = new JPanel(new java.awt.BorderLayout());
        popup.setBackground(new Color(22, 22, 22, 225));
        popup.setBorder(BorderFactory.createEmptyBorder(9, 7, 9, 7));
        popup.setVisible(false);

        volumeSlider.setOrientation(SwingConstants.VERTICAL);
        volumeSlider.setOpaque(false);
        volumeSlider.setPreferredSize(new Dimension(28, 92));
        volumeSlider.setToolTipText("音量");
        volumeSlider.putClientProperty("FlatLaf.style",
                "trackColor: #747474; thumbColor: #FFFFFF; focusWidth: 0; trackWidth: 3");
        volumeSlider.addChangeListener(event -> applyVolume(volumeSlider.getValue()));
        playerComponent.mediaPlayer().audio().setVolume(volumeSlider.getValue());
        popup.add(volumeSlider, java.awt.BorderLayout.CENTER);
        popup.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                volumeHideTimer.stop();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                scheduleVolumeHide();
            }
        });
        volumeSlider.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                volumeHideTimer.stop();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                scheduleVolumeHide();
            }
        });
        return popup;
    }

    private void configureVolumeButtonHover() {
        muteButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                showVolumePopup();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                scheduleVolumeHide();
            }
        });
    }

    private void configureCenterPlayButton() {
        centerPlayButton.setPreferredSize(new Dimension(68, 68));
        centerPlayButton.setMaximumSize(new Dimension(68, 68));
        centerPlayButton.putClientProperty("FlatLaf.style",
                "background: #99111111; hoverBackground: #BB111111; pressedBackground: #DD111111;"
                        + "foreground: #FFFFFF; focusWidth: 0; borderWidth: 0; arc: 999");
        centerPlayButton.addActionListener(event -> togglePlayback());
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
        if (volumeSlider.getValue() > 0) {
            lastAudibleVolume = volumeSlider.getValue();
            volumeSlider.setValue(0);
        } else {
            volumeSlider.setValue(Math.max(1, lastAudibleVolume));
        }
        volumeSlider.repaint();
        showControls();
    }

    private void applyVolume(int volume) {
        if (volume > 0) {
            lastAudibleVolume = volume;
        }
        playerComponent.mediaPlayer().audio().setVolume(volume);
        muted = volume == 0;
        playerComponent.mediaPlayer().audio().setMute(muted);
        updateMuteButton();
        volumeSlider.repaint();
    }

    private void updateMuteButton() {
        setButtonIcon(muteButton, muted
                ? BootstrapIcons.VOLUME_MUTE_FILL
                : BootstrapIcons.VOLUME_UP_FILL);
        muteButton.setToolTipText(muted ? "取消静音" : "静音");
    }

    private void showVolumePopup() {
        volumeHideTimer.stop();
        showControls();
        SwingUtilities.invokeLater(() -> {
            java.awt.Point button = SwingUtilities.convertPoint(muteButton, 0, 0, videoLayer);
            Dimension size = volumePopup.getPreferredSize();
            int width = Math.max(42, size.width);
            int height = Math.max(110, size.height);
            int x = Math.max(4, Math.min(videoLayer.getWidth() - width - 4,
                    button.x + (muteButton.getWidth() - width) / 2));
            int y = Math.max(4, button.y - height + 3);
            volumePopup.setBounds(x, y, width, height);
            volumePopup.setVisible(true);
            volumePopup.repaint();
        });
    }

    private void scheduleVolumeHide() {
        volumeHideTimer.restart();
    }

    private void hideVolumePopup() {
        java.awt.Point pointer;
        try {
            pointer = java.awt.MouseInfo.getPointerInfo().getLocation();
            SwingUtilities.convertPointFromScreen(pointer, videoLayer);
        } catch (RuntimeException exception) {
            volumePopup.setVisible(false);
            return;
        }
        if (!volumePopup.getBounds().contains(pointer)) {
            volumePopup.setVisible(false);
        }
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
            setButtonIcon(playButton, BootstrapIcons.PLAY_FILL);
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
        double ratio = Math.max(0, Math.min(1,
                x / (double) Math.max(1, slider.getWidth() - 1)));
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
                    singleClickTimer.stop();
                    toggleFullscreen();
                } else if (event.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(event)) {
                    singleClickTimer.restart();
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
        if (fullscreenSupported) {
            playerComponent.mediaPlayer().fullScreen().toggle();
        }
    }

    private void exitFullscreen() {
        if (fullscreenSupported && playerComponent.mediaPlayer().fullScreen().isFullScreen()) {
            playerComponent.mediaPlayer().fullScreen().set(false);
        }
    }

    void refreshVideoLayout() {
        videoLayer.revalidate();
        videoLayer.doLayout();
        playerComponent.videoSurfaceComponent().revalidate();
        playerComponent.videoSurfaceComponent().repaint();
        videoLayer.repaint();
    }

    private void showControls() {
        controls.setVisible(true);
        targetOpacity = 1f;
        fadeTimer.restart();
        scheduleControlHide();
    }

    private void scheduleControlHide() {
        overlayTimer.stop();
        if (!controlHover && playerComponent.mediaPlayer().status().isPlaying()) {
            overlayTimer.restart();
        }
    }

    private void hideControls() {
        if ((controlHover || pointerInside(controls) || pointerInside(volumePopup))
                && playerComponent.mediaPlayer().status().isPlaying()) {
            overlayTimer.restart();
            return;
        }
        if (!sliderChanging && playerComponent.mediaPlayer().status().isPlaying()) {
            targetOpacity = 0f;
            fadeTimer.restart();
        }
    }

    private boolean pointerInside(Component component) {
        if (!component.isShowing()) {
            return false;
        }
        try {
            java.awt.Point pointer = java.awt.MouseInfo.getPointerInfo().getLocation();
            SwingUtilities.convertPointFromScreen(pointer, component);
            return component.contains(pointer);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void advanceControlFade() {
        float direction = Math.signum(targetOpacity - controlOpacity);
        controlOpacity = Math.max(0, Math.min(1, controlOpacity + direction * 0.14f));
        controls.repaint();
        if (Math.abs(targetOpacity - controlOpacity) < 0.01f) {
            controlOpacity = targetOpacity;
            fadeTimer.stop();
            if (controlOpacity == 0) {
                controls.setVisible(false);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        overlayTimer.stop();
        fadeTimer.stop();
        volumeHideTimer.stop();
        singleClickTimer.stop();
        exitFullscreen();
        playerComponent.release();
    }

    private static JButton iconButton(Ikon icon, String tooltip) {
        return iconButton(icon, tooltip, 19);
    }

    private static JButton iconButton(Ikon icon, String tooltip, int size) {
        JButton button = playerButton(tooltip);
        button.setIcon(FontIcon.of(icon, size, Color.WHITE));
        return button;
    }

    private static JButton textButton(String text, String tooltip) {
        JButton button = playerButton(tooltip);
        button.setText(text);
        return button;
    }

    private static JButton playerButton(String tooltip) {
        JButton button = new JButton();
        button.setToolTipText(tooltip);
        button.setForeground(Color.WHITE);
        button.setFont(UiTheme.uiFont(java.awt.Font.BOLD, 13f));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.setPreferredSize(new Dimension(40, 32));
        button.setMaximumSize(new Dimension(54, 32));
        button.setBorder(BorderFactory.createEmptyBorder(2, 7, 2, 7));
        button.setBorderPainted(false);
        button.setContentAreaFilled(true);
        button.setOpaque(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("FlatLaf.style",
                "background: #00151515; hoverBackground: #33FFFFFF; pressedBackground: #44FFFFFF;"
                        + "foreground: #FFFFFF; focusWidth: 0; borderWidth: 0; arc: 6");
        return button;
    }

    private static void setButtonIcon(JButton button, Ikon icon) {
        button.setIcon(FontIcon.of(icon, 19, Color.WHITE));
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
            int centerSize = 68;
            centerPlayButton.setBounds(
                    Math.max(0, (width - centerSize) / 2),
                    Math.max(0, (height - centerSize) / 2),
                    centerSize,
                    centerSize);
        }
    }

    private final class PlayerControlsPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setComposite(AlphaComposite.SrcOver.derive(controlOpacity));
            copy.setPaint(new GradientPaint(
                    0, 0, new Color(0, 0, 0, 25),
                    0, getHeight(), new Color(12, 12, 12, 235)));
            copy.fillRect(0, 0, getWidth(), getHeight());
            copy.dispose();
        }

        @Override
        protected void paintChildren(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            copy.setComposite(AlphaComposite.SrcOver.derive(controlOpacity));
            super.paintChildren(copy);
            copy.dispose();
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
                setButtonIcon(playButton, BootstrapIcons.PAUSE_FILL);
                centerPlayButton.setVisible(false);
                scheduleControlHide();
            });
        }

        @Override
        public void paused(MediaPlayer mediaPlayer) {
            SwingUtilities.invokeLater(() -> {
                setButtonIcon(playButton, BootstrapIcons.PLAY_FILL);
                centerPlayButton.setVisible(true);
                showControls();
            });
        }

        @Override
        public void timeChanged(MediaPlayer mediaPlayer, long newTime) {
            SwingUtilities.invokeLater(() -> updatePosition(newTime));
        }

        @Override
        public void finished(MediaPlayer mediaPlayer) {
            SwingUtilities.invokeLater(() -> {
                playNextSegment();
                if (!playerComponent.mediaPlayer().status().isPlaying()) {
                    centerPlayButton.setVisible(true);
                }
            });
        }

        @Override
        public void error(MediaPlayer mediaPlayer) {
            SwingUtilities.invokeLater(() -> {
                noticeLabel.setText("播放失败，详情见日志");
                setButtonIcon(playButton, BootstrapIcons.PLAY_FILL);
                centerPlayButton.setVisible(true);
                showControls();
                AppLog.get(SessionPlayerPanel.class).warning("VLC playback failed");
            });
        }
    }
}
