package org.familymusic.client;

final class ConnectStateRevision {
    private long latest = -1;

    synchronized boolean accept(long revision) {
        if (revision >= 0 && revision <= latest) return false;
        if (revision >= 0) latest = revision;
        return true;
    }
}
