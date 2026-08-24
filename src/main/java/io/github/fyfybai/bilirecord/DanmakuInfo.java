package io.github.fyfybai.bilirecord;

import java.net.URI;
import java.util.List;

public record DanmakuInfo(
        long roomId,
        long uid,
        String buvid,
        String token,
        List<URI> servers) {
}
