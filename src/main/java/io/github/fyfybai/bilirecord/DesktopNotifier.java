package io.github.fyfybai.bilirecord;

import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Font;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DesktopNotifier implements AutoCloseable {
    private static final Logger LOG = AppLog.get(DesktopNotifier.class);

    private final TrayIcon trayIcon;
    private final MenuItem toggleItem;

    DesktopNotifier(Runnable showWindow, Runnable toggleMonitoring, Runnable exit) {
        if (!SystemTray.isSupported()) {
            trayIcon = null;
            toggleItem = null;
            LOG.warning("System tray is not supported on this desktop");
            return;
        }
        PopupMenu menu = new PopupMenu();
        Font menuFont = UiTheme.uiFont(Font.PLAIN, 14f);
        menu.setFont(menuFont);
        MenuItem showItem = new MenuItem("打开 biliRecord");
        showItem.setFont(menuFont);
        showItem.addActionListener(event -> SwingUtilities.invokeLater(showWindow));
        toggleItem = new MenuItem("开始监控");
        toggleItem.setFont(menuFont);
        toggleItem.addActionListener(event -> SwingUtilities.invokeLater(toggleMonitoring));
        MenuItem exitItem = new MenuItem("退出");
        exitItem.setFont(menuFont);
        exitItem.addActionListener(event -> SwingUtilities.invokeLater(exit));
        menu.add(showItem);
        menu.add(toggleItem);
        menu.addSeparator();
        menu.add(exitItem);

        trayIcon = new TrayIcon(UiTheme.brandIcon(32).getImage(), "biliRecord", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(event -> SwingUtilities.invokeLater(showWindow));
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException exception) {
            throw new IllegalStateException("Could not install system tray icon", exception);
        }
    }

    void setMonitoring(boolean monitoring) {
        if (toggleItem != null) {
            toggleItem.setLabel(monitoring ? "停止监控" : "开始监控");
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
}
