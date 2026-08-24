package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class BiliHttpClient {
    private static final URI ROOM_INFO_ENDPOINT =
            URI.create("https://api.live.bilibili.com/room/v1/Room/get_info");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI roomInfoEndpoint;

    public BiliHttpClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), new ObjectMapper(), ROOM_INFO_ENDPOINT);
    }

    BiliHttpClient(HttpClient httpClient, ObjectMapper objectMapper, URI roomInfoEndpoint) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.roomInfoEndpoint = roomInfoEndpoint;
    }

    public RoomInfo getRoomInfo(long roomId) throws IOException, InterruptedException {
        String query = "room_id=" + URLEncoder.encode(Long.toString(roomId), StandardCharsets.UTF_8);
        URI uri = URI.create(roomInfoEndpoint + "?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Referer", "https://live.bilibili.com/")
                .header("User-Agent", "biliRecord/0.1")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili room API returned HTTP " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(-1) != 0 || !root.path("data").isObject()) {
            throw new IOException("Bilibili room API error: " + root.path("message").asText("unknown error"));
        }

        JsonNode data = root.path("data");
        RoomStatus status = data.path("live_status").asInt() == 1
                ? RoomStatus.LIVE
                : RoomStatus.OFFLINE;
        return new RoomInfo(
                data.path("room_id").asLong(roomId),
                data.path("title").asText(""),
                status);
    }
}
