package io.github.fyfybai.bilirecord;

import javax.swing.JSlider;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.List;

final class TimelineSlider extends JSlider {
    TimelineSlider() {
        setMinimum(0);
        setMaximum(1);
        setValue(0);
        setToolTipText("00:00");
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                int usable = Math.max(1, getWidth() - 16);
                double ratio = Math.max(0, Math.min(1, (event.getX() - 8) / (double) usable));
                setToolTipText(formatTime(Math.round(ratio * getMaximum())));
            }
        });
    }

    void setTimeline(List<TimelineEntry> ignored, long durationMs) {
        setMaximum((int) Math.max(1, Math.min(Integer.MAX_VALUE, durationMs)));
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
