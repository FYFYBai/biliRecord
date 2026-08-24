package io.github.fyfybai.bilirecord;

public record DanmakuPacket(int version, int operation, byte[] payload) {
}
