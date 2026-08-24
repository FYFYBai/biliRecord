package io.github.fyfybai.bilirecord;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
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
    private final JFrame frame = new JFrame("biliRecord Login");
    private final JLabel qrLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("Generating QR code...", SwingConstants.CENTER);
    private final JButton refreshButton = new JButton("Refresh QR code");
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pollingTask;

    private AuthLoginWindow(AuthManager authManager) {
        this.authManager = authManager;
    }

    public static void open(AuthManager authManager) {
        SwingUtilities.invokeLater(() -> new AuthLoginWindow(authManager).show());
    }

    private void show() {
        JLabel title = new JLabel("Scan with the Bilibili app", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        title.setBorder(BorderFactory.createEmptyBorder(20, 12, 8, 12));

        qrLabel.setHorizontalAlignment(SwingConstants.CENTER);
        qrLabel.setPreferredSize(new Dimension(320, 320));

        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(event -> frame.dispose());
        refreshButton.addActionListener(event -> startLogin());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 10));
        actions.add(refreshButton);
        actions.add(cancelButton);

        JPanel footer = new JPanel(new BorderLayout());
        footer.add(statusLabel, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.SOUTH);

        frame.add(title, BorderLayout.NORTH);
        frame.add(qrLabel, BorderLayout.CENTER);
        frame.add(footer, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                executor.shutdownNow();
            }
        });
        frame.setResizable(false);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        startLogin();
    }

    private void startLogin() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        refreshButton.setEnabled(false);
        statusLabel.setText("Generating QR code...");
        qrLabel.setIcon(null);
        executor.execute(() -> {
            try {
                LoginQr qr = authManager.generateQr();
                BufferedImage image = qrImage(qr.url().toString(), 280);
                SwingUtilities.invokeLater(() -> {
                    qrLabel.setIcon(new ImageIcon(image));
                    statusLabel.setText("Waiting for scan...");
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
            SwingUtilities.invokeLater(() -> statusLabel.setText(result.message()));
            if (result.state() == LoginState.SUCCESS) {
                pollingTask.cancel(false);
                SwingUtilities.invokeLater(() -> {
                    refreshButton.setEnabled(false);
                    statusLabel.setText("Login successful. Credentials saved locally.");
                    Timer timer = new Timer(1500, event -> frame.dispose());
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
            statusLabel.setText("Login failed: " + message);
            refreshButton.setEnabled(true);
        });
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
