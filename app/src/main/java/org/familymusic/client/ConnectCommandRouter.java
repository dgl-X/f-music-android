package org.familymusic.client;

import java.util.function.Consumer;

/** Every system transport action chooses one playback destination. */
final class ConnectCommandRouter {
    enum Destination { LOCAL, REMOTE, PREPARING }

    private ConnectCommandRouter() {}

    static Destination destination(boolean enabled,String ownDevice,String activeDevice,boolean preparing){
        if(!enabled)return Destination.LOCAL;
        if(preparing)return Destination.PREPARING;
        return activeDevice!=null&&!activeDevice.isEmpty()&&!activeDevice.equals("null")&&!activeDevice.equals(ownDevice)
                ?Destination.REMOTE:Destination.LOCAL;
    }

    static void dispatch(Destination destination,String action,Runnable local,Consumer<String> remote){
        if(destination==Destination.LOCAL)local.run();
        else if(destination==Destination.REMOTE)remote.accept(action);
    }
}
