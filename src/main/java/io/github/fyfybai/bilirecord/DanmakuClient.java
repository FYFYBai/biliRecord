package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class DanmakuClient implements WebSocket.Listener, AutoCloseable {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DanmakuPacketCodec packetCodec = new DanmakuPacketCodec();
    private final DanmakuEventParser eventParser = new DanmakuEventParser();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ByteArrayOutputStream binaryMessage = new ByteArrayOutputStream();
    private final CompletableFuture<Void> authenticated = new CompletableFuture<>();
    private final CountDownLatch closed = new CountDownLatch(1);

    private DanmakuInfo info;
    private DanmakuEventHandler eventHandler;
    private WebSocket webSocket;
    private volatile Throwable failure;
    private volatile boolean closing;

    public void connect(DanmakuInfo info, DanmakuEventHandler eventHandler)
            throws IOException, InterruptedException {
        this.info = info;
        this.eventHandler = eventHandler;
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(info.servers().getFirst(), this)
                    .join();
            authenticated.get(10, TimeUnit.SECONDS);
        } catch (CompletionException exception) {
            throw new IOException("Danmaku WebSocket connection failed", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IOException("Danmaku WebSocket authentication timed out", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IOException("Danmaku WebSocket authentication failed", exception.getCause());
        }
    }

    public void listen(Duration duration) throws IOException, InterruptedException {
        if (closed.await(duration.toMillis(), TimeUnit.MILLISECONDS) && !closing) {
            throw new IOException(failure == null
                    ? "Danmaku WebSocket closed unexpectedly"
                    : "Danmaku WebSocket failed: " + failure.getMessage(), failure);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        ObjectNode auth = objectMapper.createObjectNode();
        auth.put("uid", info.uid());
        auth.put("roomid", info.roomId());
        auth.put("protover", 3);
        auth.put("buvid", info.buvid());
        auth.put("platform", "web");
        auth.put("type", 2);
        auth.put("key", info.token());
        webSocket.sendBinary(ByteBuffer.wrap(packetCodec.encode(
                DanmakuPacketCodec.OP_AUTH,
                auth.toString().getBytes(StandardCharsets.UTF_8))), true);
        webSocket.request(1);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onBinary(
            WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        synchronized (binaryMessage) {
            binaryMessage.writeBytes(chunk);
            if (last) {
                byte[] message = binaryMessage.toByteArray();
                binaryMessage.reset();
                try {
                    handlePackets(message);
                } catch (IOException exception) {
                    failure = exception;
                    authenticated.completeExceptionally(exception);
                    webSocket.abort();
                }
            }
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        heartbeatExecutor.shutdownNow();
        closed.countDown();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        failure = error;
        authenticated.completeExceptionally(error);
        heartbeatExecutor.shutdownNow();
        closed.countDown();
    }

    @Override
    public void close() {
        closing = true;
        heartbeatExecutor.shutdownNow();
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            try {
                closed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void handlePackets(byte[] data) throws IOException {
        for (DanmakuPacket packet : packetCodec.decode(data)) {
            if (packet.operation() == DanmakuPacketCodec.OP_AUTH_REPLY) {
                int code = objectMapper.readTree(packet.payload()).path("code").asInt(-1);
                if (code == 0) {
                    authenticated.complete(null);
                    sendHeartbeat();
                    heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, 30, 30, TimeUnit.SECONDS);
                } else {
                    authenticated.completeExceptionally(
                            new IOException("Bilibili rejected danmaku authentication with code " + code));
                }
            } else if (packet.operation() == DanmakuPacketCodec.OP_MESSAGE) {
                eventHandler.handle(eventParser.parse(packet.payload()));
            }
        }
    }

    private void sendHeartbeat() {
        WebSocket socket = webSocket;
        if (socket != null && !socket.isOutputClosed()) {
            socket.sendBinary(ByteBuffer.wrap(packetCodec.encode(
                    DanmakuPacketCodec.OP_HEARTBEAT,
                    "{}".getBytes(StandardCharsets.UTF_8))), true);
        }
    }
}
