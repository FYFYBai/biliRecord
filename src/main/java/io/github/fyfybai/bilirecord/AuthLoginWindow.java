package io.github.fyfybai.bilirecord;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class AuthLoginWindow {
    private final AuthManager authManager;
    private final Runnable onSuccess;
    private final JDialog dialog;
    private final JLabel qrLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("正在生成二维码...", SwingConstants.CENTER);
    private final JButton refreshButton = new JButton("刷新二维码");
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pollingTask;

    private AuthLoginWindow(JFrame owner, AuthManager authManager, Runnable onSuccess) {
        this.authManager = authManager;
        this.onSuccess = onSuccess;
        this.dialog = new JDialog(owner, "登录哔哩哔哩", false);
    }

    public static void open(AuthManager authManager) {
        SwingUtilities.invokeLater(() -> {
            UiTheme.install();
            new AuthLoginWindow(null, authManager, null).show();
        });
    }

    public static void open(JFrame owner, AuthManager authManager, Runnable onSuccess) {
        SwingUtilities.invokeLater(() -> new AuthLoginWindow(owner, authManager, onSuccess).show());
    }

    private void show() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(UiTheme.SURFACE);
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 20, 28));

        JLabel title = new JLabel("使用哔哩哔哩客户端扫码", SwingConstants.CENTER);
        title.setForeground(UiTheme.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        JLabel note = new JLabel("登录信息仅保存在本机", SwingConstants.CENTER);
        note.setForeground(UiTheme.MUTED);
        note.setBorder(BorderFactory.createEmptyBorder(6, 0, 12, 0));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(title, BorderLayout.NORTH);
        heading.add(note, BorderLayout.SOUTH);

        qrLabel.setHorizontalAlignment(SwingConstants.CENTER);
        qrLabel.setPreferredSize(new Dimension(300, 300));

        statusLabel.setForeground(UiTheme.MUTED);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        JButton cancelButton = new JButton("取消");
        UiTheme.outline(cancelButton);
        cancelButton.addActionListener(event -> dialog.dispose());
        UiTheme.accent(refreshButton);
        refreshButton.addActionListener(event -> startLogin());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        actions.setOpaque(false);
        actions.add(cancelButton);
        actions.add(refreshButton);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);

        root.add(heading, BorderLayout.NORTH);
        root.add(qrLabel, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.setIconImage(UiTheme.brandIcon(32).getImage());
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                executor.shutdownNow();
            }
        });
        dialog.setResizable(false);
        dialog.pack();
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
        startLogin();
    }

    private void startLogin() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        refreshButton.setEnabled(false);
        statusLabel.setText("正在生成二维码...");
        qrLabel.setIcon(null);
        executor.execute(() -> {
            try {
                LoginQr qr = authManager.generateQr();
                BufferedImage image = qrImage(qr.url().toString(), 260);
                SwingUtilities.invokeLater(() -> {
                    qrLabel.setIcon(new ImageIcon(image));
                    statusLabel.setText("等待扫码");
                    refreshButton.setEnabled(true);
                });
                pollingTask = executor.scheduleWithFixedDelay(() -> poll(qr), 1, 2, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException | WriterException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                showError(exception.getMessage());
            }
        });
    }

    private void poll(LoginQr qr) {
        try {
            LoginPollResult result = authManager.poll(qr);
            SwingUtilities.invokeLater(() -> statusLabel.setText(translate(result)));
            if (result.state() == LoginState.SUCCESS) {
                pollingTask.cancel(false);
                SwingUtilities.invokeLater(() -> {
                    refreshButton.setEnabled(false);
                    statusLabel.setForeground(UiTheme.GREEN);
                    statusLabel.setText("登录成功，凭据已保存在本机");
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    Timer timer = new Timer(900, event -> dialog.dispose());
                    timer.setRepeats(false);
                    timer.start();
                });
            } else if (result.state() == LoginState.EXPIRED) {
                pollingTask.cancel(false);
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            pollingTask.cancel(false);
            showError(exception.getMessage());
        }
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setForeground(new Color(0xE5484D));
            statusLabel.setText("登录失败：" + message);
            refreshButton.setEnabled(true);
        });
    }

    private static String translate(LoginPollResult result) {
        return switch (result.state()) {
            case WAITING_FOR_SCAN -> "等待扫码";
            case WAITING_FOR_CONFIRMATION -> "已扫码，请在客户端确认";
            case EXPIRED -> "二维码已过期，请刷新";
            case SUCCESS -> "登录成功";
        };
    }

    private static BufferedImage qrImage(String value, int size) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size,
                Map.of(com.google.zxing.EncodeHintType.MARGIN, 1));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        return image;
    }
}
