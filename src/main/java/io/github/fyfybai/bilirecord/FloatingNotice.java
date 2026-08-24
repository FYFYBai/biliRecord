package io.github.fyfybai.bilirecord;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

final class FloatingNotice implements AutoCloseable {
    private final Window owner;
    private JWindow window;
    private Timer closeTimer;

    FloatingNotice(Window owner) {
        this.owner = owner;
    }

    void show(String title, String message) {
        close();

        JWindow notice = new JWindow(owner);
        notice.setType(Window.Type.POPUP);
        notice.setFocusableWindowState(false);

        JPanel accent = new JPanel();
        accent.setBackground(UiTheme.BLUE);
        accent.setPreferredSize(new Dimension(4, 1));

        JLabel heading = new JLabel(title);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        heading.setForeground(UiTheme.TEXT);
        JLabel detail = new JLabel(message);
        detail.setForeground(UiTheme.MUTED);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(heading);
        text.add(Box.createVerticalStrut(4));
        text.add(detail);

        JPanel content = new JPanel(new BorderLayout(12, 0));
        content.setBackground(UiTheme.SURFACE);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER),
                BorderFactory.createEmptyBorder(14, 16, 14, 18)));
        content.add(accent, BorderLayout.WEST);
        content.add(text, BorderLayout.CENTER);
        content.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        content.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                close();
            }
        });

        notice.setContentPane(content);
        notice.pack();
        Point ownerLocation = owner.getLocationOnScreen();
        notice.setLocation(
                ownerLocation.x + owner.getWidth() - notice.getWidth() - 28,
                ownerLocation.y + 72);
        window = notice;
        notice.setVisible(true);

        closeTimer = new Timer(3_500, event -> close());
        closeTimer.setRepeats(false);
        closeTimer.start();
    }

    @Override
    public void close() {
        if (closeTimer != null) {
            closeTimer.stop();
            closeTimer = null;
        }
        if (window != null) {
            window.dispose();
            window = null;
        }
    }
}
