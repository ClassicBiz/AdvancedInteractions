package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.luminacity.advancedinteractions.AdvancedInteractions.MODID;

/**
 * Proximity triggers: run an action when a player walks into a hologram's radius, and another when they
 * leave. Configured in the hologram's meta (set with /aholo &lt;id&gt; proximity ...). Only players who
 * can see the hologram trigger it.
 */
@Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ProximityTracker {
    private ProximityTracker() {}

    public static final String META_RADIUS = "proximity.radius";
    public static final String META_ENTER = "proximity.enter";
    public static final String META_LEAVE = "proximity.leave";
    /** Seconds before the same player can trigger "enter" again (stops spam when pacing at the edge). */
    public static final String META_COOLDOWN = "proximity.cooldown";

    private static final int CHECK_EVERY_TICKS = 10;

    /** hologram -> players currently inside. */
    private static final Map<UUID, Set<UUID>> INSIDE = new HashMap<>();
    /** "holo|player" -> server tick of the last enter trigger. */
    private static final Map<String, Long> LAST_ENTER = new HashMap<>();
    private static int ticks;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++ticks % CHECK_EVERY_TICKS != 0) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        Set<UUID> active = new HashSet<>();
        for (ServerLevel stored : server.getAllLevels()) {
            // Copy: a triggered action may itself create or delete holograms.
            for (HologramDef def : new java.util.ArrayList<>(HologramSavedData.get(stored).all())) {
                double r = parse(def.meta.get(META_RADIUS));
                if (r <= 0 || def.id == null) continue;
                active.add(def.id);
                check(server, stored, def, Math.min(r, 128.0));
            }
        }
        INSIDE.keySet().retainAll(active);
        if (LAST_ENTER.size() > 8192) LAST_ENTER.clear();
    }

    private static void check(MinecraftServer server, ServerLevel stored, HologramDef def, double radius) {
        C2S_RequestSync.Anchor anchor = C2S_RequestSync.effectiveAnchor(def, server, stored);
        ServerLevel level = anchor.dim() == null ? stored : server.getLevel(anchor.dim());
        if (level == null) return;

        Set<UUID> before = INSIDE.getOrDefault(def.id, Set.of());
        Set<UUID> now = new HashSet<>();
        double r2 = radius * radius;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            if (def.privateToEntity && def.anchorEntity != null && !def.anchorEntity.equals(p.getUUID())) continue;
            if (p.position().distanceToSqr(anchor.pos()) <= r2) now.add(p.getUUID());
        }

        String enter = def.meta.get(META_ENTER);
        String leave = def.meta.get(META_LEAVE);
        long tick = server.getTickCount();
        long cooldownTicks = (long) (parse(def.meta.get(META_COOLDOWN)) * 20.0);

        for (UUID id : now) {
            if (before.contains(id) || enter == null || enter.isBlank()) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            String key = def.id + "|" + id;
            Long last = LAST_ENTER.get(key);
            if (cooldownTicks > 0 && last != null && tick - last < cooldownTicks) continue;
            HologramDef snap = C2S_RequestSync.prepareForViewer(def, p, level);
            if (snap == null) continue; // can't see it, so it doesn't greet them
            LAST_ENTER.put(key, tick);
            ActionRunner.run(p, level, def, snap, enter);
        }
        for (UUID id : before) {
            if (now.contains(id) || leave == null || leave.isBlank()) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue; // logged out
            ActionRunner.run(p, p.getLevel(), def, null, leave);
        }
        INSIDE.put(def.id, now);
    }

    private static double parse(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
