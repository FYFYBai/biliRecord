package io.github.fyfybai.bilirecord;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DesktopNotifier implements AutoCloseable {
    private static final Logger LOG = AppLog.get(DesktopNotifier.class);
    private static final int MENU_WIDTH = 190;

    private final TrayIcon trayIcon;
    private final JButton toggleItem;
    private final JDialog trayMenu;
    private long lastPopupRequestAt;

    DesktopNotifier(Runnable showWindow, Runnable toggleMonitoring, Runnable exit) {
        if (!SystemTray.isSupported()) {
            trayIcon = null;
            toggleItem = null;
            trayMenu = null;
            LOG.warning("System tray is not supported on this desktop");
            return;
        }

        Font menuFont = UiTheme.uiFont(Font.PLAIN, 14f);
        trayMenu = new JDialog();
        trayMenu.setUndecorated(true);
        trayMenu.setType(Window.Type.POPUP);
        trayMenu.setAlwaysOnTop(true);
        trayMenu.setFocusableWindowState(true);
        trayMenu.setContentPane(menuPanel(menuFont, showWindow, toggleMonitoring, exit));
        trayMenu.pack();
        trayMenu.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent event) {
                trayMenu.setVisible(false);
            }
        });

        toggleItem = (JButton) ((JPanel) trayMenu.getContentPane()).getComponent(2);
        trayIcon = new TrayIcon(UiTheme.brandIcon(32).getImage(), UiTheme.APP_NAME);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(event -> SwingUtilities.invokeLater(showWindow));
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestTrayMenu(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                requestTrayMenu(event);
            }
        });
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException exception) {
            throw new IllegalStateException("Could not install system tray icon", exception);
        }
    }

    void setMonitoring(boolean monitoring) {
        if (toggleItem != null) {
            toggleItem.setText(monitoring ? "停止监控" : "开始监控");
        }
    }

    void info(String title, String message) {
        display(title, message, TrayIcon.MessageType.INFO);
    }

    void warning(String title, String message) {
        display(title, message, TrayIcon.MessageType.WARNING);
    }

    void error(String title, String message) {
        display(title, message, TrayIcon.MessageType.ERROR);
    }

    boolean isAvailable() {
        return trayIcon != null;
    }

    @Override
    public void close() {
        if (trayMenu != null) {
            trayMenu.dispose();
        }
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (RuntimeException exception) {
                LOG.log(Level.FINE, "Could not remove tray icon", exception);
            }
        }
    }

    private JPanel menuPanel(
            Font menuFont,
            Runnable showWindow,
            Runnable toggleMonitoring,
            Runnable exit) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER),
                BorderFactory.createEmptyBorder(5, 5, 5, 5)));
        panel.add(menuItem("打开 " + UiTheme.APP_NAME, menuFont, showWindow));
        panel.add(Box.createVerticalStrut(2));
        panel.add(menuItem("开始监控", menuFont, toggleMonitoring));
        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(MENU_WIDTH - 20, 7));
        separator.setForeground(UiTheme.BORDER);
        panel.add(separator);
        panel.add(menuItem("退出", menuFont, exit));
        return panel;
    }

    private JButton menuItem(String text, Font font, Runnable action) {
        JButton button = new JButton(text);
        button.setFont(font);
        button.setForeground(UiTheme.TEXT);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setAlignmentX(JButton.LEFT_ALIGNMENT);
        button.setPreferredSize(new Dimension(MENU_WIDTH - 10, 34));
        button.setMaximumSize(new Dimension(MENU_WIDTH - 10, 34));
        button.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        button.setFocusPainted(false);
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        button.putClientProperty("FlatLaf.style",
                "background: #FFFFFF; hoverBackground: #F1F2F3; pressedBackground: #E3E5E7;"
                        + "borderWidth: 0; focusWidth: 0; arc: 6");
        button.addActionListener(event -> {
            trayMenu.setVisible(false);
            SwingUtilities.invokeLater(action);
        });
        return button;
    }

    private void display(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, type);
        }
    }

    private void requestTrayMenu(MouseEvent event) {
        if (!event.isPopupTrigger() && !SwingUtilities.isRightMouseButton(event)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPopupRequestAt < 300) {
            return;
        }
        lastPopupRequestAt = now;
        SwingUtilities.invokeLater(() -> showTrayMenu(event.getX(), event.getY()));
    }

    private void showTrayMenu(int screenX, int screenY) {
        trayMenu.setVisible(false);
        trayMenu.pack();
        int x = Math.max(0, screenX - trayMenu.getWidth());
        int y = Math.max(0, screenY - trayMenu.getHeight());
        trayMenu.setLocation(x, y);
        trayMenu.setVisible(true);
        trayMenu.toFront();
        trayMenu.requestFocus();
    }
}
