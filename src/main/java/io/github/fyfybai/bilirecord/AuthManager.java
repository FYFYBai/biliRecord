package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class AuthManager {
    private static final URI QR_GENERATE_ENDPOINT =
            URI.create("https://passport.bilibili.com/x/passport-login/web/qrcode/generate");
    private static final URI QR_POLL_ENDPOINT =
            URI.create("https://passport.bilibili.com/x/passport-login/web/qrcode/poll");

    private final HttpClient httpClient;
    private final CookieManager cookieManager;
    private final ObjectMapper objectMapper;
    private final AuthStore authStore;
    private final URI generateEndpoint;
    private final URI pollEndpoint;

    public AuthManager() {
        this(defaultCookieManager(), new ObjectMapper(), new AuthStore(),
                QR_GENERATE_ENDPOINT, QR_POLL_ENDPOINT);
    }

    private AuthManager(CookieManager cookieManager, ObjectMapper objectMapper, AuthStore authStore,
                        URI generateEndpoint, URI pollEndpoint) {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .cookieHandler(cookieManager)
                        .build(),
                cookieManager, objectMapper, authStore, generateEndpoint, pollEndpoint);
    }

    AuthManager(HttpClient httpClient, CookieManager cookieManager, ObjectMapper objectMapper,
                AuthStore authStore, URI generateEndpoint, URI pollEndpoint) {
        this.httpClient = httpClient;
        this.cookieManager = cookieManager;
        this.objectMapper = objectMapper;
        this.authStore = authStore;
        this.generateEndpoint = generateEndpoint;
        this.pollEndpoint = pollEndpoint;
    }

    public LoginQr generateQr() throws IOException, InterruptedException {
        JsonNode data = request(generateEndpoint).path("data");
        String key = data.path("qrcode_key").asText();
        String url = data.path("url").asText();
        if (key.isBlank() || url.isBlank()) {
            throw new IOException("Bilibili did not return a login QR code");
        }
        return new LoginQr(key, URI.create(url));
    }

    public LoginPollResult poll(LoginQr qr) throws IOException, InterruptedException {
        URI uri = URI.create(pollEndpoint + "?qrcode_key="
                + URLEncoder.encode(qr.key(), StandardCharsets.UTF_8));
        JsonNode data = request(uri).path("data");
        int code = data.path("code").asInt(-1);
        return switch (code) {
            case 0 -> {
                authStore.save(cookieManager.getCookieStore().getCookies(),
                        data.path("refresh_token").asText(""));
                yield new LoginPollResult(LoginState.SUCCESS, "Login successful");
            }
            case 86090 -> new LoginPollResult(LoginState.WAITING_FOR_CONFIRMATION, "Scanned; confirm in the app");
            case 86038 -> new LoginPollResult(LoginState.EXPIRED, "QR code expired");
            case 86101 -> new LoginPollResult(LoginState.WAITING_FOR_SCAN, "Waiting for scan");
            default -> throw new IOException("Bilibili login error: " + data.path("message").asText("code " + code));
        };
    }

    private JsonNode request(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", "biliRecord/0.1")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Bilibili login API returned HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IOException("Bilibili login API error: " + root.path("message").asText("unknown error"));
        }
        return root;
    }

    private static CookieManager defaultCookieManager() {
        return new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    }
}
