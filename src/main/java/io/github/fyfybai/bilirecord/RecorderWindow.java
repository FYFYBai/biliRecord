package io.github.fyfybai.bilirecord;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RecorderWindow {
    private static final Logger LOG = AppLog.get(RecorderWindow.class);
    private static final long DISK_WARNING_BYTES = 5L * 1024 * 1024 * 1024;
    private static final long DISK_CRITICAL_BYTES = 1024L * 1024 * 1024;
    private static final Path DEFAULT_RECORDINGS_DIRECTORY = Path.of("recordings")
            .toAbsolutePath().normalize();

    private final JFrame frame = new JFrame(UiTheme.APP_NAME);
    private final JTextField roomField = new JTextField();
    private final JButton monitorButton = new JButton("开始监控");
    private final JButton loginButton = new JButton("登录");
    private final JLabel accountLabel = new JLabel("未登录");
    private final JLabel stateValue = metricValue("未监控", UiTheme.MUTED);
    private final JLabel durationValue = metricValue("00:00:00", UiTheme.TEXT);
    private final JLabel storageValue = metricValue("0 B", UiTheme.TEXT);
    private final JLabel freeDiskValue = metricValue("--", UiTheme.TEXT);
    private final JLabel roomValue = new JLabel("--");
    private final JLabel uidValue = new JLabel("--");
    private final JLabel titleValue = new JLabel("尚未选择直播间");
    private final JLabel sessionValue = new JLabel("--");
    private final JProgressBar diskProgress = new JProgressBar(0, 1000);
    private final EventTableModel eventModel = new EventTableModel();
    private final SessionTableModel sessionModel = new SessionTableModel();
    private final JTable sessionTable = table(sessionModel);
    private final JButton reviewSessionButton = new JButton("回看");
    private final JButton deleteSessionButton = new JButton("删除");
    private final JTextField recordingDirectoryField = new JTextField();
    private final JButton chooseRecordingDirectoryButton = new JButton("选择位置");
    private final JTextArea logArea = new JTextArea();
    private final DesktopSettingsStore settingsStore = new DesktopSettingsStore();
    private final FloatingNotice floatingNotice = new FloatingNotice(frame);
    private final AtomicBoolean storageReadRunning = new AtomicBoolean();
    private final AtomicBoolean sessionReadRunning = new AtomicBoolean();

    private DesktopNotifier notifier;
    private AutoCloseable logSubscription;
    private Timer uiTimer;
    private volatile Thread monitorWorker;
    private volatile Path activeSession;
    private volatile Path recordingsDirectory = DEFAULT_RECORDINGS_DIRECTORY;
    private volatile Instant recordingStartedAt;
    private boolean diskWarningShown;
    private boolean diskCriticalShown;
    private boolean exiting;
    private int timerTicks;
    private long lastWarningAt;

    public void show() {
        frame.setIconImage(UiTheme.brandIcon(32).getImage());
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(920, 620));
        frame.setSize(1080, 720);
        frame.setLocationRelativeTo(null);
        frame.setContentPane(buildContent());
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (notifier != null && notifier.isAvailable()) {
                    hideToTray();
                } else {
                    exitApplication();
                }
            }
        });
        frame.addWindowStateListener(event -> {
            if ((event.getNewState() & JFrame.ICONIFIED) != 0
                    && notifier != null && notifier.isAvailable()) {
                SwingUtilities.invokeLater(this::hideToTray);
            }
        });

        notifier = new DesktopNotifier(this::showFromTray, this::toggleMonitoring, this::exitApplication);
        monitorButton.addActionListener(event -> toggleMonitoring());
        loginButton.addActionListener(event -> openLogin());
        loadSettings();
        updateLoginState();
        attachLogStream();
        refreshSessions();

        uiTimer = new Timer(1_000, event -> tick());
        uiTimer.start();
        frame.setVisible(true);
        LOG.info("Desktop UI started");
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UiTheme.BACKGROUND);
        root.add(buildTopBar(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(BorderFactory.createEmptyBorder(8, 20, 16, 20));
        tabs.putClientProperty("JTabbedPane.tabType", "underlined");
        tabs.putClientProperty("JTabbedPane.selectedBackground", UiTheme.BACKGROUND);
        tabs.addTab("监控", buildMonitorPanel());
        tabs.addTab("录制记录", buildSessionsPanel());
        tabs.addTab("日志", buildLogsPanel());
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(UiTheme.SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(12, 22, 12, 22)));

        JLabel logo = new JLabel(UiTheme.brandIcon(36));
        JLabel brand = new JLabel(UiTheme.APP_NAME);
        brand.setForeground(UiTheme.TEXT);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 20f));
        JPanel identity = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        identity.setOpaque(false);
        identity.add(logo);
        identity.add(brand);

        accountLabel.setForeground(UiTheme.MUTED);
        UiTheme.outline(loginButton);
        JPanel account = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        account.setOpaque(false);
        account.add(accountLabel);
        account.add(loginButton);
        bar.add(identity, BorderLayout.WEST);
        bar.add(account, BorderLayout.EAST);
        return bar;
    }

    private JPanel buildMonitorPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 14));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JPanel overview = new JPanel();
        overview.setOpaque(false);
        overview.setLayout(new BoxLayout(overview, BoxLayout.Y_AXIS));
        overview.add(buildRoomControl());
        overview.add(Box.createVerticalStrut(12));

        JPanel metrics = new JPanel(new GridLayout(1, 4, 12, 0));
        metrics.setOpaque(false);
        metrics.add(metric("运行状态", stateValue));
        metrics.add(metric("本次时长", durationValue));
        metrics.add(metric("本次写入", storageValue));
        metrics.add(metric("磁盘可用", freeDiskValue));
        overview.add(metrics);
        overview.add(Box.createVerticalStrut(12));
        overview.add(buildRoomDetails());
        panel.add(overview, BorderLayout.NORTH);

        JTable events = table(eventModel);
        events.getColumnModel().getColumn(0).setPreferredWidth(110);
        events.getColumnModel().getColumn(0).setMaxWidth(130);
        events.getColumnModel().getColumn(1).setPreferredWidth(90);
        events.getColumnModel().getColumn(1).setMaxWidth(110);
        panel.add(section("实时事件", new JScrollPane(events)), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildRoomControl() {
        JPanel content = new JPanel(new BorderLayout(16, 0));
        content.setOpaque(false);

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("直播间监控");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        title.setForeground(UiTheme.TEXT);
        JLabel subtitle = new JLabel("输入房间号或 live.bilibili.com 链接");
        subtitle.setForeground(UiTheme.MUTED);
        heading.add(title);
        heading.add(Box.createVerticalStrut(3));
        heading.add(subtitle);

        roomField.setPreferredSize(new Dimension(340, 38));
        UiTheme.placeholder(roomField, "例如 92613");
        monitorButton.setPreferredSize(new Dimension(108, 38));
        UiTheme.accent(monitorButton);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(roomField);
        actions.add(monitorButton);
        content.add(heading, BorderLayout.WEST);
        content.add(actions, BorderLayout.EAST);
        return surface(content, 18);
    }

    private JPanel buildRoomDetails() {
        JPanel details = new JPanel(new GridLayout(2, 4, 16, 8));
        details.setOpaque(false);
        details.add(detailLabel("房间"));
        details.add(detailLabel("主播 UID"));
        details.add(detailLabel("标题"));
        details.add(detailLabel("当前 Session"));
        configureDetailValue(roomValue);
        configureDetailValue(uidValue);
        configureDetailValue(titleValue);
        configureDetailValue(sessionValue);
        details.add(roomValue);
        details.add(uidValue);
        details.add(titleValue);
        details.add(sessionValue);

        JPanel wrapper = new JPanel(new BorderLayout(0, 10));
        wrapper.setOpaque(false);
        wrapper.add(details, BorderLayout.CENTER);
        diskProgress.setValue(0);
        diskProgress.setPreferredSize(new Dimension(10, 5));
        diskProgress.setBorderPainted(false);
        diskProgress.setForeground(UiTheme.BLUE);
        wrapper.add(diskProgress, BorderLayout.SOUTH);
        return surface(wrapper, 16);
    }

    private JPanel buildSessionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JLabel title = new JLabel("本地录制记录");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        JButton refresh = new JButton("刷新");
        UiTheme.outline(refresh);
        refresh.addActionListener(event -> refreshSessions());
        JButton open = new JButton("打开文件夹");
        UiTheme.outline(open);
        open.addActionListener(event -> openSelectedSession());
        UiTheme.accent(reviewSessionButton);
        reviewSessionButton.setEnabled(false);
        reviewSessionButton.addActionListener(event -> reviewSelectedSession());
        UiTheme.destructive(deleteSessionButton);
        deleteSessionButton.setEnabled(false);
        deleteSessionButton.addActionListener(event -> deleteSelectedSession());
        sessionTable.getSelectionModel().addListSelectionListener(event -> {
            boolean selected = sessionTable.getSelectedRow() >= 0;
            reviewSessionButton.setEnabled(selected);
            deleteSessionButton.setEnabled(selected);
        });
        sessionTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) {
                    reviewSelectedSession();
                }
            }
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(refresh);
        actions.add(deleteSessionButton);
        actions.add(open);
        actions.add(reviewSessionButton);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(title, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);

        JLabel locationLabel = new JLabel("录制保存位置");
        locationLabel.setForeground(UiTheme.MUTED);
        recordingDirectoryField.setEditable(false);
        recordingDirectoryField.setText(recordingsDirectory.toString());
        recordingDirectoryField.setToolTipText(recordingsDirectory.toString());
        recordingDirectoryField.setPreferredSize(new Dimension(10, 34));
        UiTheme.outline(chooseRecordingDirectoryButton);
        chooseRecordingDirectoryButton.addActionListener(event -> chooseRecordingDirectory());
        JPanel location = new JPanel(new BorderLayout(10, 0));
        location.setOpaque(false);
        location.add(locationLabel, BorderLayout.WEST);
        location.add(recordingDirectoryField, BorderLayout.CENTER);
        location.add(chooseRecordingDirectoryButton, BorderLayout.EAST);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(header);
        top.add(Box.createVerticalStrut(10));
        top.add(location);
        panel.add(top, BorderLayout.NORTH);
        panel.add(new JScrollPane(sessionTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildLogsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        JLabel title = new JLabel("运行日志");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        JButton open = new JButton("打开日志目录");
        UiTheme.outline(open);
        open.addActionListener(event -> openPath(AppLog.directory()));
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(title, BorderLayout.WEST);
        header.add(open, BorderLayout.EAST);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(0xFCFCFD));
        logArea.setForeground(UiTheme.TEXT);
        logArea.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private void toggleMonitoring() {
        if (monitorWorker == null) {
            startMonitoring();
        } else {
            stopMonitoring();
        }
    }

    private void startMonitoring() {
        long roomId;
        try {
            roomId = RoomIdParser.parse(roomField.getText().strip());
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(frame, exception.getMessage(),
                    "直播间格式不正确", JOptionPane.WARNING_MESSAGE);
            roomField.requestFocusInWindow();
            return;
        }
        saveSettings();
        eventModel.clear();
        activeSession = null;
        recordingStartedAt = null;
        diskWarningShown = false;
        diskCriticalShown = false;
        roomField.setEditable(false);
        chooseRecordingDirectoryButton.setEnabled(false);
        monitorButton.setText("停止监控");
        UiTheme.outline(monitorButton);
        notifier.setMonitoring(true);
        setState("等待开播", UiTheme.BLUE);
        LOG.info(() -> "Monitoring requested for room " + roomId);

        RecordingObserver observer = createObserver();
        Path targetDirectory = recordingsDirectory;
        Thread worker = Thread.ofVirtual().name("auto-recorder").unstarted(() -> {
            try {
                new AutoRecorder(targetDirectory).run(roomId, observer);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOG.info("Monitoring stopped by user");
            } catch (Exception exception) {
                LOG.log(Level.SEVERE, "Monitoring stopped unexpectedly", exception);
                SwingUtilities.invokeLater(() -> showFatal(
                        "监控异常停止", exception.getMessage(), exception));
            } finally {
                SwingUtilities.invokeLater(this::monitoringFinished);
            }
        });
        monitorWorker = worker;
        worker.start();
    }

    private void stopMonitoring() {
        Thread worker = monitorWorker;
        if (worker == null) {
            return;
        }
        monitorButton.setEnabled(false);
        setState("正在停止", UiTheme.WARNING);
        worker.interrupt();
    }

    private RecordingObserver createObserver() {
        return new RecordingObserver() {
            @Override
            public void onRoomUpdated(RoomInfo room, LifecycleState state) {
                SwingUtilities.invokeLater(() -> updateRoom(room, state));
            }

            @Override
            public void onRecordingStarted(RoomInfo room, Path sessionDirectory) {
                SwingUtilities.invokeLater(() -> {
                    activeSession = sessionDirectory;
                    recordingStartedAt = Instant.now();
                    sessionValue.setText(sessionDirectory.getFileName().toString());
                    sessionValue.setToolTipText(sessionDirectory.toString());
                    setState("录制中", UiTheme.GREEN);
                    eventModel.add(EventKind.LIVE, "直播开始，录像与事件流已连接", 0);
                    notifier.info("直播已开始", room.title());
                    refreshSessions();
                });
            }

            @Override
            public void onRecordingStopped(RoomInfo room) {
                SwingUtilities.invokeLater(() -> {
                    long endedOffset = recordingStartedAt == null
                            ? 0
                            : Duration.between(recordingStartedAt, Instant.now()).toMillis();
                    activeSession = null;
                    recordingStartedAt = null;
                    eventModel.add(EventKind.PREPARING, "直播结束，Session 已完成", endedOffset);
                    setState("等待开播", UiTheme.BLUE);
                    notifier.info("直播已结束", "房间 " + room.roomId() + " 已停止录制");
                    refreshSessions();
                });
            }

            @Override
            public void onRecovery(String message) {
                SwingUtilities.invokeLater(() -> notifier.info("录制已恢复", message));
            }

            @Override
            public void onEvent(DanmakuEvent event, long sessionOffsetMs) {
                SwingUtilities.invokeLater(() -> eventModel.add(event, sessionOffsetMs));
            }

            @Override
            public void onWarning(String operation, String message) {
                SwingUtilities.invokeLater(() -> showWarning(operation, message));
            }
        };
    }

    private void updateRoom(RoomInfo room, LifecycleState state) {
        roomValue.setText(Long.toString(room.roomId()));
        uidValue.setText(Long.toString(room.uid()));
        titleValue.setText(room.title().isBlank() ? "无标题" : room.title());
        titleValue.setToolTipText(room.title());
        switch (state) {
            case OFFLINE -> setState("未开播", UiTheme.MUTED);
            case STARTING -> setState("正在启动", UiTheme.BLUE);
            case RECORDING -> setState("录制中", UiTheme.GREEN);
            case VERIFYING_END -> setState("确认下播", UiTheme.WARNING);
            case STOPPING -> setState("正在停止", UiTheme.WARNING);
        }
    }

    private void monitoringFinished() {
        monitorWorker = null;
        activeSession = null;
        recordingStartedAt = null;
        roomField.setEditable(true);
        chooseRecordingDirectoryButton.setEnabled(true);
        monitorButton.setEnabled(true);
        monitorButton.setText("开始监控");
        UiTheme.accent(monitorButton);
        notifier.setMonitoring(false);
        setState("未监控", UiTheme.MUTED);
        durationValue.setText("00:00:00");
        refreshSessions();
    }

    private void tick() {
        timerTicks++;
        Instant started = recordingStartedAt;
        if (started != null) {
            durationValue.setText(formatDuration(Duration.between(started, Instant.now())));
        }
        if (timerTicks % 5 == 0 && activeSession != null) {
            refreshStorageStats(activeSession);
        }
        if (timerTicks % 30 == 0) {
            refreshSessions();
        }
    }

    private void refreshStorageStats(Path sessionDirectory) {
        if (!storageReadRunning.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().start(() -> {
            try {
                StorageStats stats = StorageStats.read(sessionDirectory);
                SwingUtilities.invokeLater(() -> updateStorageStats(stats));
            } catch (IOException exception) {
                LOG.log(Level.WARNING, "Could not read recording disk usage", exception);
            } finally {
                storageReadRunning.set(false);
            }
        });
    }

    private void updateStorageStats(StorageStats stats) {
        storageValue.setText(StorageStats.formatBytes(stats.sessionBytes()));
        freeDiskValue.setText(StorageStats.formatBytes(stats.usableBytes()));
        int used = (int) Math.round((1 - stats.freeRatio()) * 1000);
        diskProgress.setValue(Math.max(0, Math.min(1000, used)));
        boolean low = stats.usableBytes() < DISK_WARNING_BYTES || stats.freeRatio() < 0.05;
        if (low && !diskWarningShown) {
            diskWarningShown = true;
            String message = "录制磁盘仅剩 " + StorageStats.formatBytes(stats.usableBytes());
            LOG.warning(message);
            notifier.warning("磁盘空间不足", message);
        } else if (!low) {
            diskWarningShown = false;
        }
        if (stats.usableBytes() < DISK_CRITICAL_BYTES
                && monitorWorker != null && !diskCriticalShown) {
            diskCriticalShown = true;
            stopMonitoring();
            showFatal("磁盘空间严重不足", "剩余空间低于 1 GB，已请求停止监控。", null);
        }
    }

    private void refreshSessions() {
        if (!sessionReadRunning.compareAndSet(false, true)) {
            return;
        }
        Path directory = recordingsDirectory;
        Thread.ofVirtual().start(() -> {
            try {
                List<SessionSummary> sessions = new SessionCatalog(directory).recent(100);
                SwingUtilities.invokeLater(() -> {
                    if (directory.equals(recordingsDirectory)) {
                        updateSessions(sessions);
                    }
                });
            } catch (IOException exception) {
                LOG.log(Level.WARNING, "Could not load local sessions", exception);
            } finally {
                sessionReadRunning.set(false);
                if (!directory.equals(recordingsDirectory)) {
                    SwingUtilities.invokeLater(this::refreshSessions);
                }
            }
        });
    }

    private void chooseRecordingDirectory() {
        if (monitorWorker != null) {
            floatingNotice.show("监控运行中", "停止监控后可以更改录制保存位置");
            return;
        }
        JFileChooser chooser = new JFileChooser(recordingsDirectory.toFile());
        chooser.setDialogTitle("选择录制保存位置");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setSelectedFile(recordingsDirectory.toFile());
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path selected = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        try {
            Files.createDirectories(selected);
            if (!Files.isDirectory(selected) || !Files.isWritable(selected)) {
                throw new IOException("所选目录不可写");
            }
            settingsStore.saveRecordingDirectory(selected.toString());
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Could not change recording directory", exception);
            floatingNotice.show("无法设置录制位置", exception.getMessage());
            return;
        }
        recordingsDirectory = selected;
        recordingDirectoryField.setText(selected.toString());
        recordingDirectoryField.setToolTipText(selected.toString());
        recordingDirectoryField.setCaretPosition(0);
        diskWarningShown = false;
        diskCriticalShown = false;
        refreshSessions();
        floatingNotice.show("录制位置已更新", "新的录制将保存到所选目录");
    }

    private void updateSessions(List<SessionSummary> sessions) {
        Path selectedDirectory = null;
        int selected = sessionTable.getSelectedRow();
        if (selected >= 0) {
            selectedDirectory = sessionModel.get(sessionTable.convertRowIndexToModel(selected))
                    .directory().toAbsolutePath().normalize();
        }
        sessionModel.setSessions(sessions);
        if (selectedDirectory == null) {
            return;
        }
        for (int modelRow = 0; modelRow < sessionModel.getRowCount(); modelRow++) {
            Path directory = sessionModel.get(modelRow).directory().toAbsolutePath().normalize();
            if (directory.equals(selectedDirectory)) {
                int viewRow = sessionTable.convertRowIndexToView(modelRow);
                sessionTable.setRowSelectionInterval(viewRow, viewRow);
                break;
            }
        }
    }

    private void openSelectedSession() {
        int selected = sessionTable.getSelectedRow();
        if (selected < 0) {
            floatingNotice.show("尚未选择录制记录", "请先选择一条 Session，再打开文件夹");
            return;
        }
        int modelRow = sessionTable.convertRowIndexToModel(selected);
        openPath(sessionModel.get(modelRow).directory());
    }

    private void reviewSelectedSession() {
        int selected = sessionTable.getSelectedRow();
        if (selected < 0) {
            floatingNotice.show("尚未选择录制记录", "请先选择一条 Session，再进入回看");
            return;
        }
        int modelRow = sessionTable.convertRowIndexToModel(selected);
        ReviewWindow.open(sessionModel.get(modelRow), frame);
    }

    private void deleteSelectedSession() {
        int selected = sessionTable.getSelectedRow();
        if (selected < 0) {
            floatingNotice.show("尚未选择录制记录", "请先选择一条 Session，再执行删除");
            return;
        }
        int modelRow = sessionTable.convertRowIndexToModel(selected);
        SessionSummary session = sessionModel.get(modelRow);
        Path directory = session.directory().toAbsolutePath().normalize();
        Path current = activeSession == null ? null : activeSession.toAbsolutePath().normalize();
        if (directory.equals(current) || session.endedAt() == null) {
            floatingNotice.show("无法删除正在录制的 Session", "请先停止监控并等待录像完成");
            return;
        }
        Object[] options = {"移入回收站", "取消"};
        int choice = JOptionPane.showOptionDialog(
                frame,
                "房间 %d\n%s\n%s".formatted(
                        session.roomId(), session.title(), directory),
                "删除本地录制记录",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options,
                options[1]);
        if (choice != 0) {
            return;
        }
        deleteSessionButton.setEnabled(false);
        Thread.ofVirtual().start(() -> moveSessionToTrash(directory));
    }

    private void moveSessionToTrash(Path directory) {
        String failure = null;
        try {
            if (!Files.exists(directory)) {
                failure = "录制目录已经不存在";
            } else if (!Desktop.isDesktopSupported()
                    || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                failure = "当前系统不支持移入回收站";
            } else if (!Desktop.getDesktop().moveToTrash(directory.toFile())) {
                failure = "系统未能将录制目录移入回收站";
            }
        } catch (RuntimeException exception) {
            failure = exception.getMessage() == null ? "删除录制目录失败" : exception.getMessage();
            LOG.log(Level.WARNING, "Could not move session to trash " + directory, exception);
        }
        String result = failure;
        SwingUtilities.invokeLater(() -> {
            refreshSessions();
            if (result == null) {
                floatingNotice.show("录制记录已移入回收站", directory.getFileName().toString());
            } else {
                floatingNotice.show("无法删除录制记录", result);
                deleteSessionButton.setEnabled(sessionTable.getSelectedRow() >= 0);
            }
        });
    }

    private void openPath(Path path) {
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | UnsupportedOperationException exception) {
            showFatal("无法打开目录", exception.getMessage(), exception);
        }
    }

    private void openLogin() {
        AuthLoginWindow.open(frame, new AuthManager(), this::updateLoginState);
    }

    private void updateLoginState() {
        try {
            boolean loggedIn = new AuthStore().loadCookieHeader().isPresent();
            accountLabel.setText(loggedIn ? "本机已登录" : "未登录");
            accountLabel.setForeground(loggedIn ? UiTheme.GREEN : UiTheme.MUTED);
            loginButton.setText(loggedIn ? "切换账号" : "登录");
        } catch (IOException exception) {
            accountLabel.setText("登录状态不可用");
            LOG.log(Level.WARNING, "Could not read login state", exception);
        }
    }

    private void loadSettings() {
        try {
            DesktopSettings settings = settingsStore.load();
            roomField.setText(settings.room());
            Path directory = settings.recordingDirectory().isBlank()
                    ? DEFAULT_RECORDINGS_DIRECTORY
                    : Path.of(settings.recordingDirectory()).toAbsolutePath().normalize();
            recordingsDirectory = directory;
            recordingDirectoryField.setText(directory.toString());
            recordingDirectoryField.setToolTipText(directory.toString());
            recordingDirectoryField.setCaretPosition(0);
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.WARNING, "Could not load desktop settings", exception);
        }
    }

    private void saveSettings() {
        try {
            settingsStore.saveRoom(roomField.getText().strip());
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Could not save desktop settings", exception);
        }
    }

    private void attachLogStream() {
        for (String line : AppLog.recentLines()) {
            appendLog(line);
        }
        logSubscription = AppLog.subscribe(line -> SwingUtilities.invokeLater(() -> appendLog(line)));
    }

    private void appendLog(String line) {
        logArea.append(line);
        if (logArea.getDocument().getLength() > 500_000) {
            try {
                logArea.getDocument().remove(0, 100_000);
            } catch (javax.swing.text.BadLocationException ignored) {
                // Document length was changed by the event dispatch thread.
            }
        }
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showWarning(String operation, String message) {
        long now = System.currentTimeMillis();
        if (now - lastWarningAt > 60_000) {
            notifier.warning("运行异常", message);
            lastWarningAt = now;
        }
        setState("自动恢复中", UiTheme.WARNING);
        LOG.warning(() -> "UI warning for " + operation + ": " + message);
    }

    private void showFatal(String title, String message, Throwable failure) {
        String detail = message == null || message.isBlank() ? "未知错误" : message;
        if (failure != null) {
            LOG.log(Level.SEVERE, title + ": " + detail, failure);
        } else {
            LOG.severe(title + ": " + detail);
        }
        notifier.error(title, detail);
        if (frame.isVisible()) {
            JOptionPane.showMessageDialog(frame, detail, title, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void hideToTray() {
        frame.setVisible(false);
        notifier.info(UiTheme.APP_NAME + "仍在运行", "监控和录制已转入后台");
    }

    private void showFromTray() {
        frame.setVisible(true);
        frame.setExtendedState(JFrame.NORMAL);
        frame.toFront();
    }

    private void exitApplication() {
        if (exiting) {
            return;
        }
        exiting = true;
        Thread worker = monitorWorker;
        if (worker != null) {
            worker.interrupt();
        }
        Thread.ofVirtual().start(() -> {
            if (worker != null) {
                try {
                    worker.join(20_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            SwingUtilities.invokeLater(() -> {
                uiTimer.stop();
                floatingNotice.close();
                notifier.close();
                try {
                    if (logSubscription != null) {
                        logSubscription.close();
                    }
                } catch (Exception exception) {
                    LOG.log(Level.FINE, "Could not detach UI log listener", exception);
                }
                frame.dispose();
                System.exit(0);
            });
        });
    }

    private void setState(String text, Color color) {
        stateValue.setText(text);
        stateValue.setForeground(color);
    }

    private static JPanel metric(String label, JLabel value) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        JLabel title = new JLabel(label);
        title.setForeground(UiTheme.MUTED);
        panel.add(title);
        panel.add(Box.createVerticalStrut(7));
        panel.add(value);
        return surface(panel, 16);
    }

    private static JLabel metricValue(String text, Color color) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 20f));
        return label;
    }

    private static JLabel detailLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UiTheme.MUTED);
        label.setFont(label.getFont().deriveFont(12f));
        return label;
    }

    private static void configureDetailValue(JLabel label) {
        label.setForeground(UiTheme.TEXT);
        label.setToolTipText(label.getText());
    }

    private static JPanel surface(JPanel content, int padding) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(UiTheme.SURFACE);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER),
                BorderFactory.createEmptyBorder(padding, padding, padding, padding)));
        wrapper.add(content, BorderLayout.CENTER);
        return wrapper;
    }

    private static JPanel section(String title, JScrollPane content) {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setForeground(UiTheme.TEXT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 15f));
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JTable table(javax.swing.table.TableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(34);
        table.setShowVerticalLines(false);
        table.setGridColor(UiTheme.BORDER);
        table.setSelectionBackground(new Color(0xFFF0F5));
        table.setSelectionForeground(UiTheme.TEXT);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        DefaultTableCellRenderer header = (DefaultTableCellRenderer) table.getTableHeader()
                .getDefaultRenderer();
        header.setHorizontalAlignment(SwingConstants.LEFT);
        table.getTableHeader().setPreferredSize(new Dimension(10, 36));
        return table;
    }

    private static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        return "%02d:%02d:%02d".formatted(
                seconds / 3_600,
                seconds / 60 % 60,
                seconds % 60);
    }
}
