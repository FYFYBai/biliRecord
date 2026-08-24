package io.github.fyfybai.bilirecord;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ReviewWindow {
    private static final Logger LOG = AppLog.get(ReviewWindow.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final JFrame frame = new JFrame("录制回看 - " + UiTheme.APP_NAME);
    private final SessionTimelineReader reader = new SessionTimelineReader();
    private final TranscriptionService transcriptionService = new TranscriptionService();
    private final TimelineTableModel timelineModel = new TimelineTableModel();
    private final JTable timelineTable = new JTable(timelineModel);
    private final JTextField searchField = new JTextField();
    private final JComboBox<String> typeFilter = new JComboBox<>(new String[]{
            "全部", "开播", "下播", "弹幕", "房间", "礼物", "醒目留言", "大航海", "转录"});
    private final JComboBox<String> modelChoice = new JComboBox<>(new String[]{
            "tiny", "base", "small", "medium", "large-v3-turbo"});
    private final JComboBox<String> deviceChoice = new JComboBox<>(new String[]{"CPU", "CUDA"});
    private final JComboBox<String> languageChoice = new JComboBox<>(new String[]{"中文", "自动识别"});
    private final JButton exportButton = new JButton("导出片段");
    private final JButton transcribeButton = new JButton("生成转录");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel asrStatus = new JLabel("尚未生成转录");

    private SessionTimeline timeline;
    private SessionPlayerPanel player;
    private JPanel headerPanel;
    private JPanel timelinePanel;
    private JSplitPane splitPane;
    private int windowedDividerLocation;
    private volatile Thread transcriptionWorker;

    private ReviewWindow(SessionTimeline timeline) {
        this.timeline = timeline;
        timelineModel.setEntries(timeline.entries());
        modelChoice.setSelectedItem("small");
    }

    static void open(SessionSummary summary, JFrame owner) {
        Thread.ofVirtual().start(() -> {
            try {
                SessionTimeline timeline = new SessionTimelineReader().read(summary);
                SwingUtilities.invokeLater(() -> new ReviewWindow(timeline).show(owner));
            } catch (IOException exception) {
                LOG.log(Level.WARNING, "Could not open recording review", exception);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                        owner,
                        exception.getMessage(),
                        "无法读取录制记录",
                        JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private void show(JFrame owner) {
        frame.setIconImage(UiTheme.brandIcon(32).getImage());
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1040, 680));
        frame.setSize(1280, 780);
        frame.setLocationRelativeTo(owner);
        frame.setContentPane(buildContent());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                close();
            }
        });
        frame.setVisible(true);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UiTheme.BACKGROUND);
        headerPanel = buildHeader();
        root.add(headerPanel, BorderLayout.NORTH);

        JPanel playerPanel;
        try {
            player = new SessionPlayerPanel(timeline, frame, this::setPlayerFullscreen);
            playerPanel = player;
        } catch (RuntimeException | LinkageError exception) {
            LOG.log(Level.WARNING, "VLC player is unavailable", exception);
            playerPanel = unavailablePlayer(exception.getMessage());
        }

        timelinePanel = buildTimelinePanel();
        splitPane = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                playerPanel,
                timelinePanel);
        splitPane.setResizeWeight(0.62);
        splitPane.setDividerLocation(720);
        windowedDividerLocation = 720;
        splitPane.setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));
        splitPane.setBackground(UiTheme.BACKGROUND);
        root.add(splitPane, BorderLayout.CENTER);
        return root;
    }

    private void setPlayerFullscreen(boolean fullscreen) {
        Runnable update = () -> {
            if (headerPanel == null || splitPane == null || timelinePanel == null) {
                return;
            }
            headerPanel.setVisible(!fullscreen);
            if (fullscreen) {
                windowedDividerLocation = splitPane.getDividerLocation();
                splitPane.setRightComponent(null);
                splitPane.setDividerSize(0);
                splitPane.setBorder(null);
            } else {
                splitPane.setRightComponent(timelinePanel);
                splitPane.setDividerSize(10);
                splitPane.setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));
                splitPane.setDividerLocation(windowedDividerLocation);
            }
            frame.revalidate();
            frame.repaint();
            refreshPlayerLayout();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    private void refreshPlayerLayout() {
        if (player == null) {
            return;
        }
        player.refreshVideoLayout();
        Timer layoutTimer = new Timer(120, event -> player.refreshVideoLayout());
        layoutTimer.setRepeats(false);
        layoutTimer.start();
    }

    private JPanel buildHeader() {
        SessionSummary summary = timeline.summary();
        JPanel header = new JPanel(new BorderLayout(16, 8));
        header.setBackground(UiTheme.SURFACE);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER),
                BorderFactory.createEmptyBorder(12, 18, 12, 18)));

        JLabel title = new JLabel(summary.title().isBlank() ? "未命名直播" : summary.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JLabel details = new JLabel("房间 %d  ·  主播 UID %d  ·  %s  ·  %d 个分段".formatted(
                summary.roomId(), summary.uid(), DATE.format(summary.startedAt()), summary.segments()));
        details.setForeground(UiTheme.MUTED);
        JPanel identity = new JPanel(new GridLayout(2, 1, 0, 3));
        identity.setOpaque(false);
        identity.add(title);
        identity.add(details);
        header.add(identity, BorderLayout.CENTER);

        JPanel asr = new JPanel(new BorderLayout(0, 6));
        asr.setOpaque(false);
        modelChoice.setToolTipText("本地语音识别模型");
        deviceChoice.setToolTipText("推理设备");
        languageChoice.setToolTipText("音频语言");
        UiTheme.blue(exportButton);
        exportButton.addActionListener(event -> ClipExportWindow.open(
                frame,
                timeline,
                () -> player == null ? 0 : player.currentPositionMs()));
        UiTheme.accent(transcribeButton);
        transcribeButton.setEnabled(summary.endedAt() != null);
        transcribeButton.addActionListener(event -> toggleTranscription());
        int actionWidth = Math.max(
                transcribeButton.getPreferredSize().width,
                exportButton.getPreferredSize().width);
        Dimension actionSize = new Dimension(actionWidth, 32);
        transcribeButton.setPreferredSize(actionSize);
        exportButton.setPreferredSize(actionSize);
        JPanel transcriptionRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        transcriptionRow.setOpaque(false);
        transcriptionRow.add(modelChoice);
        transcriptionRow.add(deviceChoice);
        transcriptionRow.add(languageChoice);
        transcriptionRow.add(transcribeButton);
        asr.add(transcriptionRow, BorderLayout.NORTH);
        JPanel exportRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        exportRow.setOpaque(false);
        exportRow.add(exportButton);
        asr.add(exportRow, BorderLayout.SOUTH);
        header.add(asr, BorderLayout.EAST);
        return header;
    }

    private JPanel buildTimelinePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(UiTheme.SURFACE);
        panel.setBorder(BorderFactory.createLineBorder(UiTheme.BORDER));

        UiTheme.placeholder(searchField, "搜索弹幕、用户、礼物或转录");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                applyFilter();
            }
        });
        typeFilter.addActionListener(event -> applyFilter());
        JPanel search = new JPanel(new BorderLayout(8, 0));
        search.setOpaque(false);
        search.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
        search.add(searchField, BorderLayout.CENTER);
        search.add(typeFilter, BorderLayout.EAST);
        panel.add(search, BorderLayout.NORTH);

        timelineTable.setRowHeight(32);
        timelineTable.setShowVerticalLines(false);
        timelineTable.setGridColor(UiTheme.BORDER);
        timelineTable.setSelectionBackground(new Color(0xFFF0F5));
        timelineTable.setSelectionForeground(UiTheme.TEXT);
        timelineTable.setAutoCreateRowSorter(true);
        timelineTable.getColumnModel().getColumn(0).setPreferredWidth(92);
        timelineTable.getColumnModel().getColumn(1).setPreferredWidth(68);
        timelineTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        timelineTable.getColumnModel().getColumn(3).setPreferredWidth(260);
        timelineTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    seekSelectedEntry();
                }
            }
        });
        timelineTable.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                    event.consume();
                    seekSelectedEntry();
                }
            }
        });
        panel.add(new JScrollPane(timelineTable), BorderLayout.CENTER);

        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(140, 5));
        progress.setBorderPainted(false);
        progress.setForeground(UiTheme.PINK);
        JPanel status = new JPanel(new BorderLayout(8, 0));
        status.setOpaque(false);
        status.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        status.add(asrStatus, BorderLayout.CENTER);
        status.add(progress, BorderLayout.SOUTH);
        panel.add(status, BorderLayout.SOUTH);
        updateTranscriptionStatus();
        return panel;
    }

    private JPanel unavailablePlayer(String message) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(0x18191C));
        JLabel label = new JLabel(
                "<html><div style='text-align:center;color:#ffffff'>播放器不可用<br>"
                        + escape(message == null ? "请安装 64 位 VLC 3.x" : message)
                        + "</div></html>",
                JLabel.CENTER);
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private void applyFilter() {
        timelineModel.filter(searchField.getText(), (String) typeFilter.getSelectedItem());
    }

    private void seekSelectedEntry() {
        int row = timelineTable.getSelectedRow();
        if (row < 0 || player == null) {
            return;
        }
        TimelineEntry entry = timelineModel.get(timelineTable.convertRowIndexToModel(row));
        player.seekTo(entry.offsetMs());
    }

    private void toggleTranscription() {
        Thread worker = transcriptionWorker;
        if (worker != null) {
            transcriptionService.cancel();
            worker.interrupt();
            transcribeButton.setEnabled(false);
            asrStatus.setText("正在取消转录…");
            return;
        }
        if (timeline.transcription().available()) {
            int choice = JOptionPane.showConfirmDialog(
                    frame,
                    "重新生成会替换当前 Session 的转录，继续吗？",
                    "重新生成转录",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
        }
        transcribeButton.setText("取消转录");
        progress.setVisible(true);
        progress.setIndeterminate(true);
        setAsrControlsEnabled(false);
        TranscriptionService.Config config = new TranscriptionService.Config(
                (String) modelChoice.getSelectedItem(),
                deviceChoice.getSelectedIndex() == 0 ? "cpu" : "cuda",
                deviceChoice.getSelectedIndex() == 0 ? "int8" : "float16",
                languageChoice.getSelectedIndex() == 0 ? "zh" : "");
        transcriptionWorker = Thread.ofVirtual().start(() -> runTranscription(config));
    }

    private void runTranscription(TranscriptionService.Config config) {
        try {
            TranscriptionService.Result result = transcriptionService.transcribe(
                    timeline,
                    config,
                    (fraction, message) -> SwingUtilities.invokeLater(
                            () -> updateProgress(fraction, message)));
            timeline = reader.read(timeline.summary());
            SwingUtilities.invokeLater(() -> {
                timelineModel.setEntries(timeline.entries());
                applyFilter();
                if (player != null) {
                    player.setMarkers(timeline.entries());
                }
                asrStatus.setText("转录完成：%d 段，语言 %s".formatted(
                        result.segments().size(), result.language()));
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            SwingUtilities.invokeLater(() -> asrStatus.setText("转录已取消"));
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.WARNING, "Transcription failed", exception);
            SwingUtilities.invokeLater(() -> {
                asrStatus.setText("转录失败：" + exception.getMessage());
                JOptionPane.showMessageDialog(
                        frame,
                        exception.getMessage(),
                        "本地转录失败",
                        JOptionPane.ERROR_MESSAGE);
            });
        } finally {
            transcriptionWorker = null;
            SwingUtilities.invokeLater(() -> {
                transcribeButton.setText("生成转录");
                transcribeButton.setEnabled(true);
                progress.setVisible(false);
                setAsrControlsEnabled(true);
            });
        }
    }

    private void updateProgress(double fraction, String message) {
        asrStatus.setText(message);
        if (fraction >= 0) {
            progress.setIndeterminate(false);
            progress.setValue((int) Math.round(Math.min(1, fraction) * 1000));
        } else {
            progress.setIndeterminate(true);
        }
    }

    private void updateTranscriptionStatus() {
        TranscriptionStatus status = timeline.transcription();
        if (status.available()) {
            asrStatus.setText("已有转录：%s，%d 段，语言 %s".formatted(
                    status.model(), status.segments(), status.language()));
        } else if (timeline.summary().endedAt() == null) {
            asrStatus.setText("录制结束后可生成本地转录");
        }
    }

    private void setAsrControlsEnabled(boolean enabled) {
        modelChoice.setEnabled(enabled);
        deviceChoice.setEnabled(enabled);
        languageChoice.setEnabled(enabled);
        transcribeButton.setEnabled(true);
    }

    private void close() {
        Thread worker = transcriptionWorker;
        if (worker != null) {
            transcriptionService.cancel();
            worker.interrupt();
        }
        if (player != null) {
            player.close();
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
