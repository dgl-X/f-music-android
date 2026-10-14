package org.familymusic.client;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ConnectCommandRouterTest {
    @Test public void notificationPlayNeverStartsPhoneWhenAnotherDeviceOwnsPlayback(){
        List<String> actions=new ArrayList<>();
        ConnectCommandRouter.dispatch(ConnectCommandRouter.destination(true,"phone","web",false),"play",()->actions.add("local"),actions::add);
        assertEquals(java.util.Arrays.asList("play"),actions);
    }
    @Test public void everyTransportActionUsesTheSameRemoteDestination(){
        for(String action:new String[]{"play","pause","next","previous","seek"}){
            List<String> actions=new ArrayList<>();
            ConnectCommandRouter.dispatch(ConnectCommandRouter.destination(true,"phone","web",false),action,()->fail("Local playback was touched"),actions::add);
            assertEquals(java.util.Arrays.asList(action),actions);
        }
    }
    @Test public void preparingBlocksSystemPlaybackUntilActivation(){
        ConnectCommandRouter.dispatch(ConnectCommandRouter.destination(true,"phone","phone",true),"play",()->fail("Preparation started playing"),action->fail("Unexpected remote command"));
    }
    @Test public void localControlsRemainAvailableWithConnectDisabledOrOwnedHere(){
        assertEquals(ConnectCommandRouter.Destination.LOCAL,ConnectCommandRouter.destination(false,"phone","web",false));
        assertEquals(ConnectCommandRouter.Destination.LOCAL,ConnectCommandRouter.destination(true,"phone","phone",false));
        assertEquals(ConnectCommandRouter.Destination.LOCAL,ConnectCommandRouter.destination(true,"phone",null,false));
    }
}
