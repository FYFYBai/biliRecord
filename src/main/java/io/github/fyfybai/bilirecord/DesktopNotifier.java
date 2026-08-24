package io.github.fyfybai.bilirecord;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.JWindow;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.AWTException;
import java.awt.Color;
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
    private final JWindow trayMenuAnchor;
    private long lastPopupRequestAt;

    DesktopNotifier(Runnable showWindow, Runnable toggleMonitoring, Runnable exit) {
        if (!SystemTray.isSupported()) {
            trayIcon = null;
            toggleItem = null;
            trayMenu = null;
            trayMenuAnchor = null;
            LOG.warning("System tray is not supported on this desktop");
            return;
        }
        trayMenu = new JPopupMenu();
        trayMenuAnchor = new JWindow();
        trayMenuAnchor.setSize(1, 1);
        trayMenuAnchor.setBackground(new Color(0, 0, 0, 0));
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
        trayMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                trayMenuAnchor.setVisible(false);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent event) {
                trayMenuAnchor.setVisible(false);
            }
        });

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
            trayMenu.setVisible(false);
        }
        if (trayMenuAnchor != null) {
            trayMenuAnchor.dispose();
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
        var size = trayMenu.getPreferredSize();
        int anchorX = Math.max(size.width, screenX);
        int anchorY = Math.max(size.height, screenY);
        trayMenuAnchor.setLocation(anchorX, anchorY);
        trayMenuAnchor.setVisible(true);
        trayMenu.show(trayMenuAnchor.getContentPane(), -size.width, -size.height);
    }
}
