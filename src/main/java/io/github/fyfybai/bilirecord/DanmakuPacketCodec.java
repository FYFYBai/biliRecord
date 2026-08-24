package io.github.fyfybai.bilirecord;

import org.brotli.dec.BrotliInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

public final class DanmakuPacketCodec {
    public static final int HEADER_LENGTH = 16;
    public static final int OP_HEARTBEAT = 2;
    public static final int OP_HEARTBEAT_REPLY = 3;
    public static final int OP_MESSAGE = 5;
    public static final int OP_AUTH = 7;
    public static final int OP_AUTH_REPLY = 8;

    public byte[] encode(int operation, byte[] payload) {
        ByteBuffer packet = ByteBuffer.allocate(HEADER_LENGTH + payload.length).order(ByteOrder.BIG_ENDIAN);
        packet.putInt(HEADER_LENGTH + payload.length);
        packet.putShort((short) HEADER_LENGTH);
        packet.putShort((short) 1);
        packet.putInt(operation);
        packet.putInt(1);
        packet.put(payload);
        return packet.array();
    }

    public List<DanmakuPacket> decode(byte[] data) throws IOException {
        List<DanmakuPacket> packets = new ArrayList<>();
        decodeInto(data, packets);
        return packets;
    }

    private void decodeInto(byte[] data, List<DanmakuPacket> packets) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        while (buffer.remaining() >= HEADER_LENGTH) {
            int start = buffer.position();
            int packetLength = buffer.getInt();
            int headerLength = Short.toUnsignedInt(buffer.getShort());
            int version = Short.toUnsignedInt(buffer.getShort());
            int operation = buffer.getInt();
            buffer.getInt();

            if (headerLength < HEADER_LENGTH || packetLength < headerLength
                    || packetLength > data.length - start) {
                throw new IOException("Invalid danmaku packet length");
            }
            buffer.position(start + headerLength);
            byte[] payload = new byte[packetLength - headerLength];
            buffer.get(payload);

            if (version == 2) {
                decodeInto(decompressZlib(payload), packets);
            } else if (version == 3) {
                decodeInto(decompressBrotli(payload), packets);
            } else {
                packets.add(new DanmakuPacket(version, operation, payload));
            }
            buffer.position(start + packetLength);
        }
    }

    private static byte[] decompressZlib(byte[] payload) throws IOException {
        try (var input = new InflaterInputStream(new ByteArrayInputStream(payload))) {
            return input.readAllBytes();
        }
    }

    private static byte[] decompressBrotli(byte[] payload) throws IOException {
        try (var input = new BrotliInputStream(new ByteArrayInputStream(payload));
             var output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }
}
