package org.familymusic.client;

final class RemoteSeekGuard {
    private static final long TIMEOUT_MS = 8_000;
    private static final long CONFIRM_TOLERANCE_MS = 3_000;
    private boolean active;
    private long targetMs;
    private long startedAtMs;
    private long epoch;
    private String trackId = "";

    void start(long valueMs, String currentTrackId, long playbackEpoch, long nowMs) {
        active = true;
        targetMs = Math.max(0, valueMs);
        trackId = currentTrackId == null ? "" : currentTrackId;
        epoch = playbackEpoch;
        startedAtMs = nowMs;
    }

    long position(long serverMs, long durationMs, boolean playing, String currentTrackId, long playbackEpoch, long nowMs) {
        if (!matches(currentTrackId, playbackEpoch, nowMs)) return clamp(serverMs, durationMs);
        long optimistic = targetMs + (playing ? Math.max(0, nowMs - startedAtMs) : 0);
        return clamp(optimistic, durationMs);
    }

    void observe(long serverMs, boolean playing, String currentTrackId, long playbackEpoch, long nowMs) {
        if (!active) return;
        if (!matches(currentTrackId, playbackEpoch, nowMs)) { active = false; return; }
        long expected = targetMs + (playing ? Math.max(0, nowMs - startedAtMs) : 0);
        if (Math.abs(Math.max(0, serverMs) - expected) <= CONFIRM_TOLERANCE_MS) active = false;
    }

    void cancel() { active = false; }
    boolean active() { return active; }

    private boolean matches(String currentTrackId, long playbackEpoch, long nowMs) {
        if (!active) return false;
        if (nowMs - startedAtMs > TIMEOUT_MS || epoch != playbackEpoch || !trackId.equals(currentTrackId == null ? "" : currentTrackId)) {
            active = false;
            return false;
        }
        return true;
    }

    private static long clamp(long value, long durationMs) {
        long position = Math.max(0, value);
        return durationMs > 0 ? Math.min(position, durationMs) : position;
    }
}
