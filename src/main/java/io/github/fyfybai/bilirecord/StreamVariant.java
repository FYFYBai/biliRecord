package io.github.fyfybai.bilirecord;

import java.net.URI;
import java.util.List;

public record StreamVariant(
        String protocol,
        String format,
        String codec,
        int qualityNumber,
        int width,
        int height,
        List<Integer> acceptedQualityNumbers,
        List<URI> urls) {
}
