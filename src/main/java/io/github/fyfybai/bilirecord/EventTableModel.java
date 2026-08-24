package io.github.fyfybai.bilirecord;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

final class EventTableModel extends AbstractTableModel {
    private static final int MAX_ROWS = 500;
    private static final String[] COLUMNS = {"录像时间", "类型", "内容"};

    private final List<Row> rows = new ArrayList<>();

    void add(DanmakuEvent event, long sessionOffsetMs) {
        add(event.normalized().kind(), event.normalized().summary(), sessionOffsetMs);
    }

    void add(EventKind kind, String summary, long sessionOffsetMs) {
        rows.add(0, new Row(formatOffset(sessionOffsetMs), label(kind), summary));
        if (rows.size() > MAX_ROWS) {
            rows.removeLast();
        }
        fireTableDataChanged();
    }

    void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Row row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.offset();
            case 1 -> row.type();
            case 2 -> row.summary();
            default -> "";
        };
    }

    private static String label(EventKind kind) {
        return switch (kind) {
            case LIVE -> "开播";
            case PREPARING -> "下播";
            case DANMAKU -> "弹幕";
            case ROOM_CHANGE -> "房间";
            case GIFT -> "礼物";
            case SUPER_CHAT -> "醒目留言";
            case GUARD -> "大航海";
        };
    }

    private static String formatOffset(long offsetMs) {
        long value = Math.max(0, offsetMs);
        long totalSeconds = value / 1_000;
        return "%02d:%02d:%02d.%03d".formatted(
                totalSeconds / 3_600,
                totalSeconds / 60 % 60,
                totalSeconds % 60,
                value % 1_000);
    }

    private record Row(String offset, String type, String summary) {
    }
}
