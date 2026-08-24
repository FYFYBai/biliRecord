package io.github.fyfybai.bilirecord;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class WbiSigner {
    private static final int[] KEY_INDEXES = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
    };

    private WbiSigner() {
    }

    static String mixedKey(String imageUrl, String subUrl) {
        String source = fileStem(imageUrl) + fileStem(subUrl);
        StringBuilder key = new StringBuilder(KEY_INDEXES.length);
        for (int index : KEY_INDEXES) {
            if (index < source.length()) {
                key.append(source.charAt(index));
            }
        }
        return key.toString();
    }

    static Map<String, String> sign(Map<String, String> parameters, String mixedKey, Instant now) {
        TreeMap<String, String> sorted = new TreeMap<>(parameters);
        sorted.put("wts", Long.toString(now.getEpochSecond()));

        String query = sorted.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(filter(entry.getValue())))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        String signature;
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            signature = HexFormat.of().formatHex(
                    md5.digest((query + mixedKey).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("MD5 is required by the Java runtime", exception);
        }

        Map<String, String> signed = new LinkedHashMap<>(parameters);
        signed.put("wts", sorted.get("wts"));
        signed.put("w_rid", signature);
        return signed;
    }

    static String query(Map<String, String> parameters) {
        return parameters.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String fileStem(String url) {
        int slash = url.lastIndexOf('/');
        int dot = url.indexOf('.', slash + 1);
        return url.substring(slash + 1, dot < 0 ? url.length() : dot);
    }

    private static String filter(String value) {
        return value.replaceAll("[!'()*]", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
