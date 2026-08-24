package io.github.fyfybai.bilirecord;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class ClipExportWindow {
    private static final Logger LOG = AppLog.get(ClipExportWindow.class);

    private final SessionTimeline timeline;
    private final LongSupplier currentPosition;
    private final DesktopSettingsStore settingsStore = new DesktopSettingsStore();
    private final JDialog dialog;
    private final TimeFields start = new TimeFields();
    private final TimeFields end = new TimeFields();
    private final JComboBox<ClipExporter.Format> formatChoice =
            new JComboBox<>(ClipExporter.Format.values());
    private final JTextField outputField = new JTextField();
    private final JButton chooseOutputButton = new JButton("选择位置");
    private final JButton exportButton = new JButton("确认导出");
    private final JButton closeButton = new JButton("关闭");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JLabel status = new JLabel(" ");
    private Path exportDirectory;
    private boolean exporting;

    private ClipExportWindow(Window owner, SessionTimeline timeline, LongSupplier currentPosition) {
        this.timeline = timeline;
        this.currentPosition = currentPosition;
        dialog = new JDialog(owner, "导出片段", JDialog.ModalityType.MODELESS);
        end.setMillis(timeline.durationMs());
        exportDirectory = loadExportDirectory();
        outputField.setText(defaultOutput().toString());
    }

    static void open(Window owner, SessionTimeline timeline, LongSupplier currentPosition) {
        ClipExportWindow window = new ClipExportWindow(owner, timeline, currentPosition);
        window.show();
    }

    private void show() {
        dialog.setIconImage(UiTheme.brandIcon(32).getImage());
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setMinimumSize(new Dimension(640, 470));
        dialog.setSize(680, 500);
        dialog.setContentPane(buildContent());
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                close();
            }
        });
        dialog.setVisible(true);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(UiTheme.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));

        JLabel title = new JLabel("导出录像片段");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        root.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JPanel times = new JPanel(new GridLayout(1, 2, 18, 0));
        times.setOpaque(false);
        times.add(timeSection("起点", start, "设当前时间为起点"));
        times.add(timeSection("终点", end, "设当前时间为终点"));
        times.setMaximumSize(new Dimension(Integer.MAX_VALUE, 142));
        times.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        form.add(times);
        form.add(Box.createVerticalStrut(18));

        JLabel formatLabel = new JLabel("导出格式");
        formatLabel.setFont(formatLabel.getFont().deriveFont(Font.BOLD, 14f));
        formatLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        form.add(formatLabel);
        form.add(Box.createVerticalStrut(7));
        formatChoice.setMaximumSize(new Dimension(160, 32));
        formatChoice.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        formatChoice.addActionListener(event -> updateOutputExtension());
        form.add(formatChoice);
        form.add(Box.createVerticalStrut(14));

        JLabel outputLabel = new JLabel("导出位置");
        outputLabel.setFont(outputLabel.getFont().deriveFont(Font.BOLD, 14f));
        outputLabel.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        form.add(outputLabel);
        form.add(Box.createVerticalStrut(7));
        outputField.setEditable(false);
        JPanel output = new JPanel(new BorderLayout(8, 0));
        output.setOpaque(false);
        output.add(outputField, BorderLayout.CENTER);
        UiTheme.outline(chooseOutputButton);
        chooseOutputButton.addActionListener(event -> chooseOutput());
        output.add(chooseOutputButton, BorderLayout.EAST);
        output.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        output.setAlignmentX(JPanel.LEFT_ALIGNMENT);
        form.add(output);
        root.add(form, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(10, 8));
        footer.setOpaque(false);
        progress.setVisible(false);
        progress.setBorderPainted(false);
        progress.setForeground(UiTheme.PINK);
        footer.add(status, BorderLayout.NORTH);
        footer.add(progress, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        UiTheme.outline(closeButton);
        closeButton.addActionListener(event -> close());
        UiTheme.accent(exportButton);
        exportButton.addActionListener(event -> confirmExport());
        actions.add(closeButton);
        actions.add(exportButton);
        footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private JPanel timeSection(String title, TimeFields fields, String buttonText) {
        JPanel section = new JPanel(new BorderLayout(0, 10));
        section.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        section.add(label, BorderLayout.NORTH);

        JPanel inputs = new JPanel(new GridLayout(1, 3, 8, 0));
        inputs.setOpaque(false);
        inputs.add(labeledField("小时", fields.hours));
        inputs.add(labeledField("分钟", fields.minutes));
        inputs.add(labeledField("秒钟", fields.seconds));
        section.add(inputs, BorderLayout.CENTER);

        JButton useCurrent = new JButton(buttonText);
        UiTheme.outline(useCurrent);
        useCurrent.addActionListener(event -> fields.setMillis(currentPosition.getAsLong()));
        fields.currentButton = useCurrent;
        section.add(useCurrent, BorderLayout.SOUTH);
        return section;
    }

    private static JPanel labeledField(String label, JSpinner spinner) {
        JPanel field = new JPanel(new BorderLayout(0, 4));
        field.setOpaque(false);
        JLabel text = new JLabel(label);
        text.setForeground(UiTheme.MUTED);
        field.add(text, BorderLayout.NORTH);
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "00"));
        field.add(spinner, BorderLayout.CENTER);
        return field;
    }

    private void chooseOutput() {
        ClipExporter.Format format = selectedFormat();
        Path current = withExtension(
                Path.of(outputField.getText()).toAbsolutePath().normalize(), format);
        FileDialog chooser = new FileDialog(dialog, "设置导出位置", FileDialog.SAVE);
        chooser.setDirectory(current.getParent().toString());
        chooser.setFile(current.getFileName().toString());
        chooser.setFilenameFilter((directory, name) -> name.toLowerCase(java.util.Locale.ROOT)
                .endsWith("." + format.extension()));
        chooser.setVisible(true);
        if (chooser.getFile() != null) {
            Path selected = withExtension(
                    Path.of(chooser.getDirectory(), chooser.getFile()), format)
                    .toAbsolutePath().normalize();
            outputField.setText(selected.toString());
            exportDirectory = selected.getParent();
            rememberExportDirectory();
        }
    }

    private void confirmExport() {
        long startMs = start.millis();
        long endMs = end.millis();
        if (startMs >= endMs) {
            showValidation("终点必须晚于起点");
            return;
        }
        if (endMs > timeline.durationMs()) {
            showValidation("终点不能超过录像总时长 " + formatTime(timeline.durationMs()));
            return;
        }
        ClipExporter.Format format = selectedFormat();
        Path output = withExtension(Path.of(outputField.getText()), format);
        outputField.setText(output.toString());
        var parts = ClipExporter.parts(timeline, startMs, endMs);
        if (parts.isEmpty()) {
            showValidation("所选时间范围内没有录像内容");
            return;
        }
        long availableMs = parts.stream().mapToLong(ClipExporter.Part::durationMs).sum();
        String overwrite = java.nio.file.Files.exists(output) ? "\n该文件已存在，将被覆盖。" : "";
        String message = "起点：%s\n终点：%s\n实际可导出：%s\n格式：%s\n文件：%s%s"
                .formatted(
                        formatTime(startMs),
                        formatTime(endMs),
                        formatTime(availableMs),
                        format,
                        output.toAbsolutePath(),
                        overwrite);
        int choice = JOptionPane.showConfirmDialog(
                dialog,
                message,
                "确认导出片段",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        beginExport(startMs, endMs, output, format);
    }

    private void beginExport(
            long startMs,
            long endMs,
            Path output,
            ClipExporter.Format format) {
        setExporting(true);
        status.setText("正在导出…");
        progress.setValue(0);
        progress.setVisible(true);
        Thread.ofVirtual().start(() -> {
            try {
                ClipExporter.Result result = new ClipExporter().export(
                        timeline,
                        startMs,
                        endMs,
                        output,
                        format,
                        fraction -> SwingUtilities.invokeLater(
                                () -> progress.setValue((int) Math.round(fraction * 1000))));
                SwingUtilities.invokeLater(() -> exportComplete(result));
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.log(Level.WARNING, "Could not export clip", exception);
                SwingUtilities.invokeLater(() -> exportFailed(exception));
            }
        });
    }

    private void exportComplete(ClipExporter.Result result) {
        setExporting(false);
        progress.setValue(1000);
        status.setText("导出完成：" + result.output().getFileName());
        LOG.info("Exported clip to " + result.output());
        Window owner = dialog.getOwner();
        dialog.dispose();
        JOptionPane.showMessageDialog(
                owner,
                "片段已导出到：\n" + result.output(),
                "导出完成",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void exportFailed(Exception exception) {
        setExporting(false);
        progress.setVisible(false);
        status.setText("导出失败");
        JOptionPane.showMessageDialog(
                dialog,
                exception.getMessage(),
                "无法导出片段",
                JOptionPane.ERROR_MESSAGE);
    }

    private void setExporting(boolean value) {
        exporting = value;
        start.setEnabled(!value);
        end.setEnabled(!value);
        formatChoice.setEnabled(!value);
        chooseOutputButton.setEnabled(!value);
        exportButton.setEnabled(!value);
    }

    private void close() {
        if (exporting) {
            JOptionPane.showMessageDialog(
                    dialog,
                    "导出正在进行，请等待完成",
                    "正在导出",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        dialog.dispose();
    }

    private void showValidation(String message) {
        JOptionPane.showMessageDialog(
                dialog,
                message,
                "导出时间无效",
                JOptionPane.WARNING_MESSAGE);
    }

    private Path defaultOutput() {
        ClipExporter.Format format = selectedFormat();
        String name = "clip_%s-%s.%s".formatted(
                fileTime(0), fileTime(timeline.durationMs()), format.extension());
        return exportDirectory.resolve(name).toAbsolutePath();
    }

    private Path loadExportDirectory() {
        try {
            String saved = settingsStore.load().exportDirectory();
            if (!saved.isBlank()) {
                return Path.of(saved).toAbsolutePath().normalize();
            }
        } catch (IOException | RuntimeException exception) {
            LOG.log(Level.WARNING, "Could not load export directory", exception);
        }
        return timeline.summary().directory().resolve("exports").toAbsolutePath().normalize();
    }

    private void rememberExportDirectory() {
        try {
            settingsStore.saveExportDirectory(exportDirectory.toString());
        } catch (IOException exception) {
            LOG.log(Level.WARNING, "Could not remember export directory", exception);
        }
    }

    private void updateOutputExtension() {
        outputField.setText(withExtension(
                Path.of(outputField.getText()), selectedFormat()).toString());
    }

    private ClipExporter.Format selectedFormat() {
        return (ClipExporter.Format) formatChoice.getSelectedItem();
    }

    private static Path withExtension(Path path, ClipExporter.Format format) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return path.resolveSibling(base + "." + format.extension());
    }

    private static String fileTime(long millis) {
        long seconds = Math.max(0, millis) / 1_000;
        return "%02d%02d%02d".formatted(seconds / 3_600, seconds / 60 % 60, seconds % 60);
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0, millis) / 1_000;
        return "%02d:%02d:%02d".formatted(seconds / 3_600, seconds / 60 % 60, seconds % 60);
    }

    private static final class TimeFields {
        private final JSpinner hours = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
        private final JSpinner minutes = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
        private final JSpinner seconds = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
        private JButton currentButton;

        private long millis() {
            long totalSeconds = ((Number) hours.getValue()).longValue() * 3_600
                    + ((Number) minutes.getValue()).longValue() * 60
                    + ((Number) seconds.getValue()).longValue();
            return totalSeconds * 1_000;
        }

        private void setMillis(long millis) {
            long totalSeconds = Math.max(0, millis) / 1_000;
            hours.setValue((int) Math.min(9999, totalSeconds / 3_600));
            minutes.setValue((int) (totalSeconds / 60 % 60));
            seconds.setValue((int) (totalSeconds % 60));
        }

        private void setEnabled(boolean enabled) {
            hours.setEnabled(enabled);
            minutes.setEnabled(enabled);
            seconds.setEnabled(enabled);
            currentButton.setEnabled(enabled);
        }
    }
}
