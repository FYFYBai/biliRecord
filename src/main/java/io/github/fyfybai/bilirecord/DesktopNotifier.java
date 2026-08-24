package io.github.fyfybai.bilirecord;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Font;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DesktopNotifier implements AutoCloseable {
    private static final Logger LOG = AppLog.get(DesktopNotifier.class);

    private final TrayIcon trayIcon;
    private final JMenuItem toggleItem;
    private final JPopupMenu trayMenu;

    DesktopNotifier(Runnable showWindow, Runnable toggleMonitoring, Runnable exit) {
        if (!SystemTray.isSupported()) {
            trayIcon = null;
            toggleItem = null;
            trayMenu = null;
            LOG.warning("System tray is not supported on this desktop");
            return;
        }
        trayMenu = new JPopupMenu();
        Font menuFont = UiTheme.uiFont(Font.PLAIN, 14f);
        trayMenu.setFont(menuFont);
        JMenuItem showItem = new JMenuItem("打开 " + UiTheme.APP_NAME);
        showItem.setFont(menuFont);
        showItem.addActionListener(event -> SwingUtilities.invokeLater(showWindow));
        toggleItem = new JMenuItem("开始监控");
        toggleItem.setFont(menuFont);
        toggleItem.addActionListener(event -> SwingUtilities.invokeLater(toggleMonitoring));
        JMenuItem exitItem = new JMenuItem("退出");
        exitItem.setFont(menuFont);
        exitItem.addActionListener(event -> SwingUtilities.invokeLater(exit));
        trayMenu.add(showItem);
        trayMenu.add(toggleItem);
        trayMenu.addSeparator();
        trayMenu.add(exitItem);

        trayIcon = new TrayIcon(UiTheme.brandIcon(32).getImage(), UiTheme.APP_NAME);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(event -> SwingUtilities.invokeLater(showWindow));
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (event.isPopupTrigger() || SwingUtilities.isRightMouseButton(event)) {
                    SwingUtilities.invokeLater(() -> showTrayMenu(event.getX(), event.getY()));
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    SwingUtilities.invokeLater(() -> showTrayMenu(event.getX(), event.getY()));
                }
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
            trayMenu.setVisible(false);
        }
        if (trayIcon != null) {
            try {
                SystemTray.getSystemTray().remove(trayIcon);
            } catch (RuntimeException exception) {
                LOG.log(Level.FINE, "Could not remove tray icon", exception);
            }
        }
    }

    private void display(String title, String message, TrayIcon.MessageType type) {
        if (trayIcon != null) {
            trayIcon.displayMessage(title, message, type);
        }
    }

    private void showTrayMenu(int screenX, int screenY) {
        trayMenu.setVisible(false);
        var size = trayMenu.getPreferredSize();
        trayMenu.setLocation(
                Math.max(0, screenX - size.width),
                Math.max(0, screenY - size.height));
        trayMenu.setInvoker(trayMenu);
        trayMenu.setVisible(true);
    }
}
