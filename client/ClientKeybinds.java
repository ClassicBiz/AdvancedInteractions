package com.luminacity.advancedinteractions.client;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import static com.luminacity.advancedinteractions.AdvancedInteractions.MODID;

@Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientKeybinds {
    public static KeyMapping INTERACT = new KeyMapping("key.advancedinteractions.interact", GLFW.GLFW_KEY_R, "key.categories.gameplay");
    /** Ops: edit the hologram you're looking at, or open the hologram browser. */
    public static KeyMapping EDITOR = new KeyMapping("key.advancedinteractions.editor", GLFW.GLFW_KEY_H, "key.categories.gameplay");

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent e) {
        e.register(INTERACT);
        e.register(EDITOR);
    }
}
