package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DanmakuInfoResolver {
    private static final URI DANMAKU_INFO_ENDPOINT =
            URI.create("https://api.live.bilibili.com/room/v1/Danmu/getConf");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthStore authStore;
    private final URI endpoint;

    public DanmakuInfoResolver() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(), new AuthStore(), DANMAKU_INFO_ENDPOINT);
    }

    DanmakuInfoResolver(HttpClient httpClient, ObjectMapper objectMapper,
                        AuthStore authStore, URI endpoint) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.authStore = authStore;
        this.endpoint = endpoint;
    }

    public DanmakuInfo resolve(long roomId) throws IOException, InterruptedException {
        URI uri = URI.create(endpoint + "?room_id=" + roomId + "&platform=pc&player=web");
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Referer", "https://live.bilibili.com/" + roomId)
                .header("User-Agent", "biliRecord/0.1")
                .GET();
        authStore.loadCookieHeader().ifPresent(cookie -> requestBuilder.header("Cookie", cookie));

        HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili danmaku API returned HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(-1) != 0 || !root.path("data").isObject()) {
            throw new IOException("Bilibili danmaku API error: " + root.path("message").asText("unknown error"));
        }

        JsonNode data = root.path("data");
        List<URI> servers = new ArrayList<>();
        JsonNode hosts = data.path("host_server_list");
        for (JsonNode host : hosts) {
            servers.add(URI.create("wss://" + host.path("host").asText()
                    + ":" + host.path("wss_port").asInt(443) + "/sub"));
        }
        if (servers.isEmpty()) {
            throw new IOException("Bilibili returned no danmaku servers");
        }

        long uid = authStore.loadCookie("DedeUserID").map(Long::parseLong).orElse(0L);
        String buvid = authStore.loadCookie("buvid3").orElse("");
        return new DanmakuInfo(roomId, uid, buvid, data.path("token").asText(), servers);
    }
}
