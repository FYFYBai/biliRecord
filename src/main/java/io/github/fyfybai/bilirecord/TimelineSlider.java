package io.github.fyfybai.bilirecord;

import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Graphics;
import java.util.List;

final class TimelineSlider extends JSlider {
    private List<TimelineEntry> entries = List.of();

    TimelineSlider() {
        setMinimum(0);
        setMaximum(1);
        setValue(0);
    }

    void setTimeline(List<TimelineEntry> entries, long durationMs) {
        this.entries = List.copyOf(entries);
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
        for (TimelineEntry entry : entries) {
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
}
