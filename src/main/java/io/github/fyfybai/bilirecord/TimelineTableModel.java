package io.github.fyfybai.bilirecord;

import javax.swing.table.AbstractTableModel;
import java.util.List;

final class TimelineTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"时间", "类型", "用户", "内容"};

    private List<TimelineEntry> all = List.of();
    private List<TimelineEntry> visible = List.of();

    void setEntries(List<TimelineEntry> entries) {
        all = List.copyOf(entries);
        visible = all;
        fireTableDataChanged();
    }

    void filter(String query, String type) {
        visible = all.stream()
                .filter(entry -> type == null || type.equals("全部") || entry.type().equals(type))
                .filter(entry -> entry.matches(query))
                .toList();
        fireTableDataChanged();
    }

    TimelineEntry get(int row) {
        return visible.get(row);
    }

    List<TimelineEntry> allEntries() {
        return all;
    }

    @Override
    public int getRowCount() {
        return visible.size();
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
        TimelineEntry entry = visible.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> entry.formattedOffset();
            case 1 -> entry.type();
            case 2 -> entry.author();
            case 3 -> entry.text();
            default -> "";
        };
    }
}
