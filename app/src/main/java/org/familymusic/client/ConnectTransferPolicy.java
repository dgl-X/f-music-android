package org.familymusic.client;

final class ConnectTransferPolicy {
    private ConnectTransferPolicy() {}

    static boolean useRemoteSnapshot(String targetDeviceId, String ownDeviceId, boolean remoteActive) {
        return remoteActive && ownDeviceId != null && ownDeviceId.equals(targetDeviceId);
    }
}
