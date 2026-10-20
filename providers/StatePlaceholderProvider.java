package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.state.PlayerState;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * Built-in {state.*}: the viewing player's own variables, set by button actions
 * (advholo:state_set / state_toggle / state_add / state_clear).
 */
public final class StatePlaceholderProvider implements Placeholders.PlaceholderProvider {

    @Override public Map<String, String> keys() {
        return Map.of("<key>", "This player's variable; {state.key:default} when unset. "
                + "Set by advholo:state_set?key=k&value=v, state_toggle?key=k, state_add?key=k&amount=1, state_clear?key=k");
    }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (ctx == null || !(ctx.viewer() instanceof ServerPlayer sp) || key == null) return "";
        String v = PlayerState.get(sp.getServer()).get(sp.getUUID(), key);
        return v != null ? v : (args == null ? "" : args);
    }
}
