package org.familymusic.client;

import java.util.List;

/** Queue selection rules independent from the Media3 transport. */
final class PlaybackQueuePolicy {
    enum SelectionAction { REUSE, REBUILD }

    private PlaybackQueuePolicy() {}

    static SelectionAction selectionAction(List<String> queueIds, String selectedId) {
        return selectedId != null && queueIds.contains(selectedId)
                ? SelectionAction.REUSE
                : SelectionAction.REBUILD;
    }
}
