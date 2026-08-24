package io.github.fyfybai.bilirecord;

import java.time.Duration;

final class RetryBackoff {
    private static final long[] DELAYS_SECONDS = {1, 2, 5, 10, 30};

    private int attempt;

    Duration nextDelay() {
        int index = Math.min(attempt, DELAYS_SECONDS.length - 1);
        attempt++;
        return Duration.ofSeconds(DELAYS_SECONDS[index]);
    }

    void reset() {
        attempt = 0;
    }
}
