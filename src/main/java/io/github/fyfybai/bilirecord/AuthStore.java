package io.github.fyfybai.bilirecord;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.net.HttpCookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class AuthStore {
    private static final Path DEFAULT_PATH = Path.of("data", "auth", "cookies.json");

    private final Path path;
    private final ObjectMapper objectMapper;

    public AuthStore() {
        this(DEFAULT_PATH);
    }

    AuthStore(Path path) {
        this.path = path;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public void save(Collection<HttpCookie> cookies, String refreshToken) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (HttpCookie cookie : cookies) {
            if (!cookie.hasExpired() && cookie.getDomain() != null
                    && cookie.getDomain().endsWith("bilibili.com")) {
                values.put(cookie.getName(), cookie.getValue());
            }
        }
        if (!values.containsKey("SESSDATA")) {
            throw new IOException("Login succeeded without a SESSDATA cookie");
        }

        Files.createDirectories(path.getParent());
        objectMapper.writeValue(path.toFile(),
                new StoredAuth(java.time.Instant.now().toString(), refreshToken, values));
    }

    public Optional<String> loadCookieHeader() throws IOException {
        StoredAuth auth = load();
        if (auth == null) {
            return Optional.empty();
        }
        String header = auth.cookies().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("; "));
        return header.isBlank() ? Optional.empty() : Optional.of(header);
    }

    public Optional<String> loadCookie(String name) throws IOException {
        StoredAuth auth = load();
        return auth == null ? Optional.empty() : Optional.ofNullable(auth.cookies().get(name));
    }

    public Path path() {
        return path;
    }

    private StoredAuth load() throws IOException {
        return Files.exists(path) ? objectMapper.readValue(path.toFile(), StoredAuth.class) : null;
    }

    private record StoredAuth(String updatedAt, String refreshToken, Map<String, String> cookies) {
    }
}
