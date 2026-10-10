package org.familymusic.client;

final class SmartCachePolicy {
    static final long MEANINGFUL_LISTEN_MS = 20_000L;

    enum OfflineAction { KEEP, SKIP, PAUSE }

    private SmartCachePolicy() {}

    static boolean mayCompleteTrack(boolean enabled, boolean wifiOnly, boolean unmeteredNetwork) {
        return enabled && (!wifiOnly || unmeteredNetwork);
    }

    static OfflineAction transitionAction(boolean validatedNetwork, boolean currentAvailable,
                                          boolean eligibleTransition, boolean cachedContinuation) {
        if (validatedNetwork || currentAvailable || !eligibleTransition) return OfflineAction.KEEP;
        return cachedContinuation ? OfflineAction.SKIP : OfflineAction.PAUSE;
    }

    static OfflineAction sourceErrorAction(boolean validatedNetwork, boolean cachedContinuation) {
        if (validatedNetwork) return OfflineAction.KEEP;
        return cachedContinuation ? OfflineAction.SKIP : OfflineAction.PAUSE;
    }
}
