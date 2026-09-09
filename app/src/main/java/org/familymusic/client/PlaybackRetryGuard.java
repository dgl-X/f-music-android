package org.familymusic.client;

final class PlaybackRetryGuard {
    static final int MAX_ATTEMPTS = 3;

    static final class Decision {
        final long token;
        final int attempt;
        final boolean retry;

        Decision(long token, int attempt, boolean retry) {
            this.token = token;
            this.attempt = attempt;
            this.retry = retry;
        }
    }

    private String mediaId = "";
    private int attempts;
    private long generation;

    void transition(String nextMediaId) {
        generation++;
        attempts = 0;
        mediaId = nextMediaId == null ? "" : nextMediaId;
    }

    Decision onError(String failedMediaId) {
        String id = failedMediaId == null ? "" : failedMediaId;
        if (!id.equals(mediaId)) transition(id);
        attempts++;
        generation++;
        return new Decision(generation, attempts, attempts <= MAX_ATTEMPTS);
    }

    boolean isCurrent(long token, String currentMediaId) {
        return token == generation && mediaId.equals(currentMediaId == null ? "" : currentMediaId);
    }

    boolean hasAttempts() { return attempts > 0; }

    void recovered() {
        generation++;
        attempts = 0;
    }
}
