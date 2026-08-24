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

public final class StreamResolver {
    private static final int SOURCE_QUALITY = 10_000;
    private static final URI PLAY_INFO_ENDPOINT =
            URI.create("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI playInfoEndpoint;
    private final AuthStore authStore;

    public StreamResolver() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new ObjectMapper(), PLAY_INFO_ENDPOINT, new AuthStore());
    }

    StreamResolver(HttpClient httpClient, ObjectMapper objectMapper, URI playInfoEndpoint) {
        this(httpClient, objectMapper, playInfoEndpoint, null);
    }

    private StreamResolver(HttpClient httpClient, ObjectMapper objectMapper, URI playInfoEndpoint,
                           AuthStore authStore) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.playInfoEndpoint = playInfoEndpoint;
        this.authStore = authStore;
    }

    public PlayInfo resolve(long roomId) throws IOException, InterruptedException {
        URI uri = URI.create(playInfoEndpoint
                + "?room_id=" + roomId
                + "&protocol=0,1&format=0,1,2&codec=0,1"
                + "&qn=" + SOURCE_QUALITY
                + "&platform=web&ptype=8");
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Referer", "https://live.bilibili.com/" + roomId)
                .header("User-Agent", "biliRecord/0.1")
                .GET();
        if (authStore != null) {
            authStore.loadCookieHeader().ifPresent(cookie -> requestBuilder.header("Cookie", cookie));
        }
        HttpRequest request = requestBuilder.build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili PlayInfo API returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(-1) != 0 || !root.path("data").isObject()) {
            throw new IOException("Bilibili PlayInfo API error: " + root.path("message").asText("unknown error"));
        }

        JsonNode data = root.path("data");
        RoomStatus status = data.path("live_status").asInt() == 1
                ? RoomStatus.LIVE
                : RoomStatus.OFFLINE;
        JsonNode playUrl = data.path("playurl_info").path("playurl");
        return new PlayInfo(
                data.path("room_id").asLong(roomId),
                data.path("uid").asLong(),
                status,
                parseQualities(playUrl.path("g_qn_desc")),
                parseStreams(playUrl.path("stream")));
    }

    private static List<QualityInfo> parseQualities(JsonNode nodes) {
        List<QualityInfo> qualities = new ArrayList<>();
        for (JsonNode node : nodes) {
            JsonNode mediaDescription = node.path("media_base_desc");
            String resolution = mediaDescription.path("brief_desc").path("desc").asText();
            if (resolution.isBlank()) {
                resolution = mediaDescription.path("detail_desc").path("desc").asText();
            }
            qualities.add(new QualityInfo(
                    node.path("qn").asInt(),
                    node.path("desc").asText(),
                    resolution));
        }
        return qualities;
    }

    private static List<StreamVariant> parseStreams(JsonNode streamNodes) {
        List<StreamVariant> streams = new ArrayList<>();
        for (JsonNode streamNode : streamNodes) {
            String protocol = streamNode.path("protocol_name").asText();
            for (JsonNode formatNode : streamNode.path("format")) {
                String format = formatNode.path("format_name").asText();
                for (JsonNode codecNode : formatNode.path("codec")) {
                    JsonNode mediaInfo = codecNode.path("media_info");
                    streams.add(new StreamVariant(
                            protocol,
                            format,
                            codecNode.path("codec_name").asText(),
                            codecNode.path("current_qn").asInt(),
                            mediaInfo.path("width").asInt(),
                            mediaInfo.path("height").asInt(),
                            parseIntegers(codecNode.path("accept_qn")),
                            parseUrls(codecNode.path("base_url").asText(), codecNode.path("url_info"))));
                }
            }
        }
        return streams;
    }

    private static List<Integer> parseIntegers(JsonNode nodes) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode node : nodes) {
            values.add(node.asInt());
        }
        return values;
    }

    private static List<URI> parseUrls(String baseUrl, JsonNode urlInfoNodes) {
        List<URI> urls = new ArrayList<>();
        for (JsonNode node : urlInfoNodes) {
            urls.add(URI.create(node.path("host").asText() + baseUrl + node.path("extra").asText()));
        }
        return urls;
    }
}
