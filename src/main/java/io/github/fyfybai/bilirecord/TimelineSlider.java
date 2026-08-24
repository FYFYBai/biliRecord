package io.github.fyfybai.bilirecord;

import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.List;

final class TimelineSlider extends JSlider {
    private boolean hovered;

    TimelineSlider() {
        setMinimum(0);
        setMaximum(1);
        setValue(0);
        setToolTipText("00:00");
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                double ratio = Math.max(0, Math.min(1,
                        event.getX() / (double) Math.max(1, getWidth() - 1)));
                setToolTipText(formatTime(Math.round(ratio * getMaximum())));
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hovered = false;
                repaint();
            }
        });
    }

    void setTimeline(List<TimelineEntry> ignored, long durationMs) {
        setMaximum((int) Math.max(1, Math.min(Integer.MAX_VALUE, durationMs)));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth();
        int y = getHeight() / 2;
        int trackHeight = 3;
        int progressWidth = (int) Math.round(
                width * ((getValue() - getMinimum()) / (double) Math.max(1, getMaximum() - getMinimum())));
        copy.setColor(new Color(255, 255, 255, 115));
        copy.fillRect(0, y - trackHeight / 2, width, trackHeight);
        copy.setColor(new Color(0xFB7299));
        copy.fillRect(0, y - trackHeight / 2, progressWidth, trackHeight);
        if (hovered || getValueIsAdjusting()) {
            int thumb = 10;
            int x = Math.max(0, Math.min(width - 1, progressWidth));
            copy.fillOval(x - thumb / 2, y - thumb / 2, thumb, thumb);
        }
        copy.dispose();
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0, millis) / 1_000;
        if (seconds < 3_600) {
            return "%02d:%02d".formatted(seconds / 60, seconds % 60);
        }
        return "%02d:%02d:%02d".formatted(
                seconds / 3_600,
                seconds / 60 % 60,
                seconds % 60);
    }
}
