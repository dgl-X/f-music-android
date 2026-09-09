package org.familymusic.client;

final class QualitySelector {
    private QualitySelector() {}

    static String playback(boolean aac192Ready, boolean aac96Ready, String requested) {
        if ("compact".equals(requested)) return aac96Ready ? "aac_96" : "original";
        if ("high".equals(requested) || "auto".equals(requested)) return aac192Ready ? "aac_192" : "original";
        return "original";
    }
}
