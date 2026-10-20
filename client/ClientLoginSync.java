package com.luminacity.advancedinteractions.client;

import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import static com.luminacity.advancedinteractions.AdvancedInteractions.MODID;

@Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientLoginSync {
    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        NetworkHandler.CHANNEL.sendToServer(new C2S_RequestSync());
    }

    /** Drop everything from the previous server so nothing carries over into the next session. */
    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        HologramClientManager.clear();
        ClientPlaceholderCache.clear();
        PanelClickCache.clear();
    }
}
