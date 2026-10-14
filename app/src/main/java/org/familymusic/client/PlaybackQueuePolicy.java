package org.familymusic.client;

import java.util.List;

/** Queue selection rules independent from the Media3 transport. */
final class PlaybackQueuePolicy {
    enum SelectionAction { REUSE, REBUILD }

    private PlaybackQueuePolicy() {}

    static int shuffleCurrentIndex(List<String> queueIds, String liveCurrentId) {
        return liveCurrentId == null ? -1 : queueIds.indexOf(liveCurrentId);
    }

    static SelectionAction selectionAction(List<String> queueIds, String selectedId, String currentSource, String requestedSource) {
        return selectedId != null && queueIds.contains(selectedId)
                && requestedSource != null && requestedSource.equals(currentSource)
                ? SelectionAction.REUSE
                : SelectionAction.REBUILD;
    }
}
