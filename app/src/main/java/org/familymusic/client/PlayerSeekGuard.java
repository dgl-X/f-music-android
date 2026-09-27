package org.familymusic.client;

final class PlayerSeekGuard {
    static final class Result {
        final long fromMs;
        final long toMs;

        Result(long fromMs, long toMs) {
            this.fromMs = fromMs;
            this.toMs = toMs;
        }
    }

    private boolean tracking;
    private long startMs;

    void start(long currentPositionMs) {
        tracking = true;
        startMs = Math.max(0, currentPositionMs);
    }

    boolean allowsPeriodicUpdate() {
        return !tracking;
    }

    Result finish(long selectedPositionMs) {
        long target = Math.max(0, selectedPositionMs);
        Result result = new Result(startMs, target);
        tracking = false;
        return result;
    }

    void cancel() {
        tracking = false;
    }
}
