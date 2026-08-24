package io.github.fyfybai.bilirecord;

import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.List;

final class TimelineSlider extends JSlider {
    private List<TimelineEntry> markers = List.of();

    TimelineSlider() {
        setMinimum(0);
        setMaximum(1);
        setValue(0);
        setToolTipText("00:00");
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                setToolTipText(tooltipAt(event.getX()));
            }
        });
    }

    void setTimeline(List<TimelineEntry> entries, long durationMs) {
        markers = entries.stream()
                .filter(entry -> entry.source() == TimelineSource.TRANSCRIPT
                        || !entry.type().equals("弹幕"))
                .toList();
        setMaximum((int) Math.max(1, Math.min(Integer.MAX_VALUE, durationMs)));
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        int width = getWidth() - 16;
        if (width <= 0 || getMaximum() <= 0) {
            return;
        }
        int y = Math.max(2, getHeight() / 2 - 9);
        for (TimelineEntry entry : markers) {
            long bounded = Math.max(0, Math.min(getMaximum(), entry.offsetMs()));
            int x = 8 + (int) Math.round(width * (bounded / (double) getMaximum()));
            graphics.setColor(markerColor(entry));
            graphics.drawLine(x, y, x, y + 5);
        }
    }

    private static Color markerColor(TimelineEntry entry) {
        if (entry.source() == TimelineSource.TRANSCRIPT) {
            return UiTheme.BLUE;
        }
        return switch (entry.type()) {
            case "醒目留言", "礼物", "大航海" -> UiTheme.PINK;
            default -> new Color(0x909399);
        };
    }

    private String tooltipAt(int mouseX) {
        int usable = Math.max(1, getWidth() - 16);
        TimelineEntry nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (TimelineEntry entry : markers) {
            int markerX = 8 + (int) Math.round(
                    usable * (entry.offsetMs() / (double) Math.max(1, getMaximum())));
            int distance = Math.abs(mouseX - markerX);
            if (distance <= 6 && distance < nearestDistance) {
                nearest = entry;
                nearestDistance = distance;
            }
        }
        if (nearest != null) {
            String content = nearest.text().isBlank() ? nearest.type() : nearest.text();
            return nearest.formattedOffset() + " · " + nearest.type() + " · " + content;
        }
        double ratio = Math.max(0, Math.min(1, (mouseX - 8) / (double) usable));
        return formatTime(Math.round(ratio * getMaximum()));
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
