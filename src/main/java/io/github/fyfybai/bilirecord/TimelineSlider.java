package io.github.fyfybai.bilirecord;

import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.List;

final class TimelineSlider extends JSlider {
    private boolean hovered;
    private int previewX;
    private long previewMillis = -1;

    TimelineSlider() {
        setMinimum(0);
        setMaximum(1);
        setValue(0);
        setToolTipText("00:00");
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                showPreviewAt(event.getX());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                showPreviewAt(event.getX());
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hovered = true;
                showPreviewAt(event.getX());
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hovered = false;
                if (!getValueIsAdjusting()) {
                    previewMillis = -1;
                }
                repaint();
            }
        });
    }

    void setTimeline(List<TimelineEntry> ignored, long durationMs) {
        setMaximum((int) Math.max(1, Math.min(Integer.MAX_VALUE, durationMs)));
    }

    void showPreviewAt(int x) {
        previewX = Math.max(0, Math.min(Math.max(0, getWidth() - 1), x));
        double ratio = previewX / (double) Math.max(1, getWidth() - 1);
        previewMillis = Math.round(ratio * getMaximum());
        setToolTipText(formatTime(previewMillis));
        repaint();
    }

    void hidePreview() {
        previewMillis = -1;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth();
        int y = Math.max(2, getHeight() - 6);
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
        if ((hovered || getValueIsAdjusting()) && previewMillis >= 0) {
            paintPreview(copy, width);
        }
        copy.dispose();
    }

    private void paintPreview(Graphics2D graphics, int width) {
        String text = formatTime(previewMillis);
        Font font = getFont().deriveFont(Font.BOLD, 11f);
        graphics.setFont(font);
        FontMetrics metrics = graphics.getFontMetrics(font);
        int bubbleWidth = metrics.stringWidth(text) + 14;
        int bubbleHeight = 20;
        int bubbleX = Math.max(2, Math.min(width - bubbleWidth - 2,
                previewX - bubbleWidth / 2));
        int bubbleY = 1;
        graphics.setColor(new Color(15, 15, 15, 220));
        graphics.fillRoundRect(bubbleX, bubbleY, bubbleWidth, bubbleHeight, 6, 6);
        graphics.setColor(Color.WHITE);
        graphics.drawString(text,
                bubbleX + (bubbleWidth - metrics.stringWidth(text)) / 2,
                bubbleY + (bubbleHeight - metrics.getHeight()) / 2 + metrics.getAscent());
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
