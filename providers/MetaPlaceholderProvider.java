package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;

import java.util.Map;

/** Built-in {meta.*}: values stored on the hologram with /aholo <id> meta set. */
public final class MetaPlaceholderProvider implements Placeholders.PlaceholderProvider {
    @Override public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (ctx == null || ctx.holo() == null) return null;
        if (ctx.holo().meta == null) return null;
        return ctx.holo().meta.get(key);
    }

    @Override public boolean isClientSide(String key) { return true; }

    @Override public Map<String, String> keys() {
        return Map.of("<key>", "A value set with /aholo <id> meta set <key> <value>");
    }
}
