
package com.luminacity.advancedinteractions.api;

import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.server.level.ServerLevel;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Data sources for hologram templates.
 *
 * <p>A hologram's {@code binding} (set with {@code /holo <id> data bind <binding>}) selects a provider;
 * its values are exposed through the {@code {data.<key>}} placeholder namespace (and the bare
 * {@code {key}} shorthand used by templates).</p>
 */
public final class DataProviders {

    public interface DataContext {
        Object get(String key);
        default List<Map<String,Object>> list(String key) { return Collections.emptyList(); }
        default int minPeriodTicks() { return 20; }
    }

    public interface DataProvider {
        boolean canHandle(String binding);
        DataContext fetch(String binding, ServerLevel level, HologramDef holo);
    }

    private static final List<DataProvider> REGISTRY = new CopyOnWriteArrayList<>();
    private static volatile DataProvider FALLBACK = null;

    private static final DataContext EMPTY = key -> null;

    // Respect DataContext#minPeriodTicks so templates don't hammer providers every sync.
    private record Cached(DataContext ctx, long gameTime) {}
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    public static void register(DataProvider p) {
        if (p != null) REGISTRY.add(p);
    }

    /** Provider consulted after all registered ones (used for the built-in "namespace:args" bridge). */
    public static void setFallback(DataProvider p) {
        FALLBACK = p;
    }

    public static DataContext fetch(String binding, ServerLevel level, HologramDef holo) {
        if (binding == null || binding.isBlank()) return EMPTY;

        String key = binding + "|" + (holo == null || holo.id == null ? "" : holo.id);
        long now = level == null ? 0L : level.getGameTime();
        Cached c = CACHE.get(key);
        if (c != null && level != null && now - c.gameTime() < Math.max(1, c.ctx().minPeriodTicks())) {
            return c.ctx();
        }

        DataContext out = null;
        for (var p : REGISTRY) {
            if (p.canHandle(binding)) {
                out = p.fetch(binding, level, holo);
                break;
            }
        }
        DataProvider fb = FALLBACK;
        if (out == null && fb != null && fb.canHandle(binding)) out = fb.fetch(binding, level, holo);
        if (out == null) out = EMPTY;

        if (CACHE.size() > 2048) CACHE.clear();
        CACHE.put(key, new Cached(out, now));
        return out;
    }

    public static void clearCache() {
        CACHE.clear();
    }
}
