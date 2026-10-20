package com.luminacity.advancedinteractions.client;

import com.luminacity.advancedinteractions.AdvancedInteractions;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.editor.ImagePackets;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Textures for image widgets: resource-pack textures by id, server images downloaded once per session.
 */
public final class ClientImages {
    private ClientImages() {}

    private enum State { LOADING, READY, MISSING }
    private record Entry(State state, ResourceLocation texture, int width, int height) {}

    private static final Map<String, Entry> SERVER = new ConcurrentHashMap<>();

    /**
     * Texture for an image source, or null while a server image is still downloading or doesn't exist.
     * "ns:path" is a resource-pack texture ("mypack:holo/logo" means "mypack:textures/holo/logo.png").
     */
    public static ResourceLocation resolve(String source) {
        if (source == null || source.isBlank()) return null;
        String s = source.trim();
        if (s.indexOf(':') > 0) {
            String full = s.endsWith(".png") ? s : s.replaceFirst(":", ":textures/") + ".png";
            return ResourceLocation.tryParse(full.toLowerCase(Locale.ROOT));
        }
        String name = s.toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png")) name = name + ".png";
        Entry e = SERVER.get(name);
        if (e == null) {
            SERVER.put(name, new Entry(State.LOADING, null, 0, 0));
            if (NetworkHandler.CHANNEL != null && Minecraft.getInstance().getConnection() != null) {
                NetworkHandler.CHANNEL.sendToServer(new ImagePackets.Request(name));
            }
            return null;
        }
        return e.state() == State.READY ? e.texture() : null;
    }

    /** True if a server image was looked up and the server doesn't have it. */
    public static boolean missing(String source) {
        if (source == null || source.indexOf(':') > 0) return false;
        String name = source.trim().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png")) name = name + ".png";
        Entry e = SERVER.get(name);
        return e != null && e.state() == State.MISSING;
    }

    public static void receive(String name, byte[] png) {
        if (png == null || png.length == 0) {
            SERVER.put(name, new Entry(State.MISSING, null, 0, 0));
            return;
        }
        try {
            NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
            ResourceLocation rl = new ResourceLocation(AdvancedInteractions.MODID,
                    "holo_img/" + name.replaceAll("[^a-z0-9_./-]", "_"));
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
            SERVER.put(name, new Entry(State.READY, rl, img.getWidth(), img.getHeight()));
        } catch (Exception e) {
            SERVER.put(name, new Entry(State.MISSING, null, 0, 0));
        }
    }

    /** Forget downloaded images (the server's set changed, or we left the server). */
    public static void clear() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (Entry e : SERVER.values()) if (e.texture() != null) tm.release(e.texture());
        SERVER.clear();
    }
}
