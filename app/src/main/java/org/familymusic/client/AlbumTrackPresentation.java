package org.familymusic.client;

final class AlbumTrackPresentation {
    private AlbumTrackPresentation() {}

    static String number(int trackNumber, int fallbackPosition) {
        return String.valueOf(trackNumber > 0 ? trackNumber : Math.max(1, fallbackPosition));
    }

    static boolean startsDisc(int discNumber, int previousDisc, boolean multiDisc) {
        return multiDisc && Math.max(1, discNumber) != previousDisc;
    }

    static String discTitle(int discNumber) {
        return "ДИСК " + Math.max(1, discNumber);
    }
}
