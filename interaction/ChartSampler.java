package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.panel.ChartWidget;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.luminacity.advancedinteractions.AdvancedInteractions.MODID;

/**
 * Samples every chart widget's source on the server and keeps its recent history (in memory; charts
 * start empty after a restart). Viewers receive the history through the {chart.data:<widget>}
 * placeholder, which the sync sends automatically for visible charts.
 */
@Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChartSampler {
    private ChartSampler() {}

    private record Series(Deque<Double> values, long nextSampleTick) {}
    /** "holoId|widgetId" -> history. */
    private static final Map<String, Series> HISTORY = new ConcurrentHashMap<>();
    private static int ticks;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++ticks % 20 != 0) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        Set<String> live = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            boolean pause = com.luminacity.advancedinteractions.config.AIConfig.get(
                    com.luminacity.advancedinteractions.config.AIConfig.PAUSE_WITHOUT_VIEWERS, true);
            for (HologramDef def : new ArrayList<>(HologramSavedData.get(level).all())) {
                // Nobody near enough to see it: keep the history but don't sample (config pauseWithoutViewers).
                boolean watched = !pause || hasViewer(level, def);
                for (List<PanelWidget> list : lists(def)) {
                    for (PanelWidget w : list) {
                        if (!(w instanceof ChartWidget cw) || def.id == null) continue;
                        String key = def.id + "|" + cw.id();
                        live.add(key);
                        if (watched) sample(key, cw, def, level, now);
                    }
                }
            }
        }
        HISTORY.keySet().retainAll(live);
    }

    private static boolean hasViewer(ServerLevel level, HologramDef def) {
        double r = Math.min(def.range > 0 ? def.range : 64, com.luminacity.advancedinteractions.config.AIConfig.maxViewDistance());
        double r2 = r * r;
        for (var p : level.players()) {
            if (p.distanceToSqr(def.x, def.y, def.z) <= r2) return true;
        }
        return def.anchorEntity != null; // follows a player: assume watched
    }

    private static List<List<PanelWidget>> lists(HologramDef d) {
        List<List<PanelWidget>> out = new ArrayList<>();
        out.add(d.widgets);
        out.addAll(d.pages);
        out.addAll(d.menus.values());
        out.addAll(d.views.values());
        for (var vp : d.viewPages.values()) out.addAll(vp);
        out.removeIf(l -> l == null);
        return out;
    }

    private static void sample(String key, ChartWidget cw, HologramDef def, ServerLevel level, long now) {
        Series s = HISTORY.get(key);
        if (s != null && now < s.nextSampleTick()) return;
        Deque<Double> values = s == null ? new ArrayDeque<>() : s.values();
        String raw = Placeholders.resolveToken(cw.sourceToken(), new Placeholders.PHContext(level, null, def));
        Double v = parse(raw);
        if (v != null) {
            values.addLast(v);
            while (values.size() > cw.points()) values.removeFirst();
        }
        HISTORY.put(key, new Series(values, now + cw.interval() * 20L));
    }

    private static Double parse(String raw) {
        if (raw == null) return null;
        String t = raw.replaceAll("§.", "").replace(",", "").replace("%", "").trim();
        int sp = t.indexOf(' ');
        if (sp > 0) t = t.substring(0, sp);
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    /** History as "v1,v2,..." (oldest first), or "" if none yet. */
    public static String csv(java.util.UUID holo, String widgetId) {
        if (holo == null || widgetId == null) return "";
        Series s = HISTORY.get(holo + "|" + widgetId);
        if (s == null || s.values().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Double v : s.values()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", ""));
        }
        return sb.toString();
    }

    /** {chart.data:<widgetId>} for the hologram in context. */
    public static final class Provider implements Placeholders.PlaceholderProvider {
        @Override public String resolve(String key, String args, Placeholders.PHContext ctx) {
            if (!"data".equalsIgnoreCase(key) || ctx == null || ctx.holo() == null) return "";
            return csv(ctx.holo().id, args);
        }

        @Override public Map<String, String> keys() {
            return Map.of("data:<widget id>", "Recent samples of a chart widget (used by the chart itself)");
        }
    }
}
