package io.github.fyfybai.bilirecord;

import javax.swing.table.AbstractTableModel;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

final class SessionTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"开始时间", "房间", "标题", "分段", "大小", "状态"};
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private List<SessionSummary> sessions = List.of();

    void setSessions(List<SessionSummary> sessions) {
        this.sessions = List.copyOf(sessions);
        fireTableDataChanged();
    }

    SessionSummary get(int row) {
        return sessions.get(row);
    }

    @Override
    public int getRowCount() {
        return sessions.size();
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
        SessionSummary session = sessions.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> TIME.format(session.startedAt());
            case 1 -> session.roomId();
            case 2 -> session.title();
            case 3 -> session.segments();
            case 4 -> StorageStats.formatBytes(session.bytes());
            case 5 -> session.endedAt() == null ? "录制中" : "已完成";
            default -> "";
        };
    }
}
