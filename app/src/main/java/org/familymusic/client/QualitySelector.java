package org.familymusic.client;

final class QualitySelector {
    private QualitySelector() {}

    static String playback(boolean aac192Ready, boolean aac96Ready, String requested) {
        // Server selects the actual compatible bytes lazily. Catalog readiness
        // can be stale and must not change a queue item's cache identity.
        if ("compact".equals(requested) || "aac_96".equals(requested)) return "aac_96";
        if ("high".equals(requested) || "auto".equals(requested) || "aac_192".equals(requested)) return "aac_192";
        return "original";
    }
}
