package io.github.fyfybai.bilirecord;

import java.io.IOException;

@FunctionalInterface
public interface DanmakuEventHandler {
    void handle(DanmakuEvent event) throws IOException;
}
