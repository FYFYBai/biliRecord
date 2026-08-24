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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DanmakuInfoResolver {
    private static final URI DANMAKU_INFO_ENDPOINT =
            URI.create("https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo");
    private static final URI NAV_ENDPOINT =
            URI.create("https://api.bilibili.com/x/web-interface/nav");
    private static final URI BUVID_ENDPOINT =
            URI.create("https://api.bilibili.com/x/frontend/finger/spi");
    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AuthStore authStore;
    private final URI endpoint;
    private final boolean signedRequests;
    private String sessionBuvid = "";

    public DanmakuInfoResolver() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                new ObjectMapper(), new AuthStore(), DANMAKU_INFO_ENDPOINT, true);
    }

    DanmakuInfoResolver(HttpClient httpClient, ObjectMapper objectMapper,
                        AuthStore authStore, URI endpoint) {
        this(httpClient, objectMapper, authStore, endpoint, false);
    }

    private DanmakuInfoResolver(HttpClient httpClient, ObjectMapper objectMapper,
                                AuthStore authStore, URI endpoint, boolean signedRequests) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.authStore = authStore;
        this.endpoint = endpoint;
        this.signedRequests = signedRequests;
    }

    public DanmakuInfo resolve(long roomId) throws IOException, InterruptedException {
        String buvid = signedRequests ? resolveBuvid() : authStore.loadCookie("buvid3").orElse("");
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("id", Long.toString(roomId));
        parameters.put("type", "0");
        if (signedRequests) {
            parameters = WbiSigner.sign(parameters, resolveWbiKey(), Instant.now());
        }
        URI uri = URI.create(endpoint + "?" + WbiSigner.query(parameters));
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Origin", "https://live.bilibili.com")
                .header("Referer", "https://live.bilibili.com/" + roomId)
                .header("User-Agent", WEB_USER_AGENT)
                .GET();
        String cookieHeader = cookieHeader(buvid);
        if (!cookieHeader.isBlank()) {
            requestBuilder.header("Cookie", cookieHeader);
        }

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
        JsonNode hosts = data.path("host_list");
        if (!hosts.isArray()) {
            hosts = data.path("host_server_list");
        }
        for (JsonNode host : hosts) {
            servers.add(URI.create("wss://" + host.path("host").asText()
                    + ":" + host.path("wss_port").asInt(443) + "/sub"));
        }
        if (servers.isEmpty()) {
            throw new IOException("Bilibili returned no danmaku servers");
        }

        long uid = authStore.loadCookie("DedeUserID").map(Long::parseLong).orElse(0L);
        return new DanmakuInfo(roomId, uid, buvid, data.path("token").asText(), servers);
    }

    private String resolveWbiKey() throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(NAV_ENDPOINT)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", WEB_USER_AGENT)
                .GET();
        authStore.loadCookieHeader().ifPresent(cookie -> request.header("Cookie", cookie));
        JsonNode root = sendJson(request.build(), "WBI key");
        JsonNode wbiImage = root.path("data").path("wbi_img");
        String mixedKey = WbiSigner.mixedKey(
                wbiImage.path("img_url").asText(),
                wbiImage.path("sub_url").asText());
        if (mixedKey.isBlank()) {
            throw new IOException("Bilibili returned no WBI key");
        }
        return mixedKey;
    }

    private String resolveBuvid() throws IOException, InterruptedException {
        String stored = authStore.loadCookie("buvid3").orElse("");
        if (!stored.isBlank()) {
            return stored;
        }
        if (!sessionBuvid.isBlank()) {
            return sessionBuvid;
        }
        HttpRequest request = HttpRequest.newBuilder(BUVID_ENDPOINT)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", WEB_USER_AGENT)
                .GET()
                .build();
        JsonNode root = sendJson(request, "buvid");
        sessionBuvid = root.path("data").path("b_3").asText();
        return sessionBuvid;
    }

    private JsonNode sendJson(HttpRequest request, String operation)
            throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili " + operation + " API returned HTTP "
                    + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IOException("Bilibili " + operation + " API error: "
                    + root.path("message").asText("unknown error"));
        }
        return root;
    }

    private String cookieHeader(String buvid) throws IOException {
        String stored = authStore.loadCookieHeader().orElse("");
        if (buvid.isBlank() || stored.contains("buvid3=")) {
            return stored;
        }
        return stored.isBlank() ? "buvid3=" + buvid : stored + "; buvid3=" + buvid;
    }
}
