package com.luminacity.advancedinteractions.client;

import com.luminacity.advancedinteractions.hologram.ClientHologram;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestPlaceholderRefresh;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.luminacity.advancedinteractions.AdvancedInteractions.MODID;

/**
 * Keeps holograms "alive" without requiring manual /holo syncholo region.
 *
 * Strategy:
 * - Frequently refresh ONLY server-resolved placeholder values for visible holograms (cheap).
 * - Occasionally request a full nearby sync to catch view/layout/ownership/perms changes (heavier).
 */
@Mod.EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientAutoSync {

    // Tuning:
    private static final int PLACEHOLDER_REFRESH_TICKS = 10; // 0.5s @ 20tps (visible only)
    private static final int MAX_HOLOS_PER_REFRESH = 64;

    private static int tickCounter = 0;

    // Movement-based sync (no fixed timer): request a nearby sync when you move far enough.
    private static double lastSyncX = Double.NaN;
    private static double lastSyncY = Double.NaN;
    private static double lastSyncZ = Double.NaN;
    private static String lastDim = "";
    private static final double FULL_SYNC_MOVE_SQ = 32.0 * 32.0; // 32 blocks
    // Safety net only: perms/view changes are primarily caught by server-side change detection.
    // Keep this slower to avoid constant heavy full-sync traffic.
    private static final int FULL_SYNC_PERIOD_TICKS = 400; // 20s @ 20tps
    private static int ticksSinceFullSync = FULL_SYNC_PERIOD_TICKS;


    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        tickCounter++;
        ticksSinceFullSync++;

        // Placeholder refresh: keeps dynamic values up to date.
        if (tickCounter % PLACEHOLDER_REFRESH_TICKS == 0) {
            List<ClientHologram> visible = new ArrayList<>(HologramClientManager.visibleNow(HologramClientManager.GLOBAL_RENDER_CAP_SQ));
            visible.sort(Comparator.comparingDouble(h -> h.resolvePos(mc).distanceToSqr(mc.player.position())));

            List<UUID> ids = new ArrayList<>();
            for (ClientHologram h : visible) {
                if (h == null || h.id() == null) continue;
                ids.add(h.id());
                if (ids.size() >= MAX_HOLOS_PER_REFRESH) break;
            }

            if (!ids.isEmpty()) {
                NetworkHandler.CHANNEL.sendToServer(new C2S_RequestPlaceholderRefresh(ids));
            }
        }
        
        // Nearby full sync (movement-based): catches structural changes without a timer.
        String dim = mc.level.dimension().location().toString();
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();

        boolean need = Double.isNaN(lastSyncX) || !dim.equals(lastDim);
        if (!need) {
            double dx = x - lastSyncX;
            double dy = y - lastSyncY;
            double dz = z - lastSyncZ;
            if ((dx*dx + dy*dy + dz*dz) >= FULL_SYNC_MOVE_SQ) need = true;
        }
        // Only run periodic safety sync while holograms are visible.
        if (!need && ticksSinceFullSync >= FULL_SYNC_PERIOD_TICKS
                && !HologramClientManager.visibleNow(HologramClientManager.GLOBAL_RENDER_CAP_SQ).isEmpty()) {
            need = true;
        }

        if (need) {
            lastSyncX = x; lastSyncY = y; lastSyncZ = z;
            lastDim = dim;
            ticksSinceFullSync = 0;
            NetworkHandler.CHANNEL.sendToServer(new C2S_RequestSync());
        }

// Keep counter from overflowing.
        if (tickCounter > 1_000_000) tickCounter = 0;
    }
}
