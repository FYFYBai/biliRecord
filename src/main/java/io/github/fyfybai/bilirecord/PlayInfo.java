package io.github.fyfybai.bilirecord;

import java.util.List;

public record PlayInfo(
        long roomId,
        long uid,
        RoomStatus status,
        List<QualityInfo> qualities,
        List<StreamVariant> streams) {
}
