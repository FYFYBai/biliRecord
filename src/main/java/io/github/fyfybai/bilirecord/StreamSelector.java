package io.github.fyfybai.bilirecord;

import java.util.Comparator;

public final class StreamSelector {
    private static final int TARGET_WIDTH = 1920;
    private static final int TARGET_HEIGHT = 1080;

    public StreamVariant selectPrefer1080p(PlayInfo playInfo) {
        return playInfo.streams().stream()
                .filter(stream -> !stream.urls().isEmpty())
                .min(Comparator
                        .comparingInt(StreamSelector::distanceFrom1080p)
                        .thenComparing(Comparator.comparingInt(StreamVariant::qualityNumber).reversed())
                        .thenComparingInt(StreamSelector::protocolPreference))
                .orElseThrow(() -> new IllegalStateException("No playable stream is available"));
    }

    private static int distanceFrom1080p(StreamVariant stream) {
        if (stream.width() <= 0 || stream.height() <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.abs(stream.width() - TARGET_WIDTH)
                + 2 * Math.abs(stream.height() - TARGET_HEIGHT);
    }

    private static int protocolPreference(StreamVariant stream) {
        return "http_stream".equals(stream.protocol()) ? 0 : 1;
    }
}
