package com.luminacity.advancedinteractions.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.editor.PlayerEditing;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import com.luminacity.advancedinteractions.templates.TemplateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Lets other mods give a player a hologram made from a snapshot template, e.g. a shop mod's storefront
 * above a chest. The hologram belongs to the player (they can restyle it in the editor) and follows the
 * same rules as /myholo: the player limits, allowed actions and where it may be placed. It doesn't count
 * toward the player's own hologram limit (the calling mod decides how many there are), and the player
 * can't remove its links or move it far from them. AdvancedInteractions
 * knows nothing about the other mod's content; the template (and its placeholders) are the mod's own.
 *
 * <p>Easy to call with reflection: only Minecraft and Java types.</p>
 */
public final class HologramFactory {
    private HologramFactory() {}

    /** The new hologram's id and name, or null id with the reason it wasn't made. */
    public record Created(UUID id, String alias, String problem) {}

    /**
     * @param template snapshot template JSON (the format /aholo templates save)
     * @param above    the block it hovers over (centred), {@code height} blocks above its bottom
     * @param links    blocks to link by name (e.g. "shop" -> the chest), in the same dimension
     */
    public static Created createFromJson(ServerPlayer owner, ServerLevel level, BlockPos above, double height, String alias,
                                         String template, Map<String, BlockPos> links) {
        return createFromJson(owner, level, above, height, alias, template, links, Float.NaN);
    }

    /**
     * @param yawDeg fixed facing (vanilla yaw: the direction a player looking at its front faces, SOUTH=0,
     *               WEST=90, NORTH=180, EAST=-90); NaN = always turn to face the viewer
     */
    public static Created createFromJson(ServerPlayer owner, ServerLevel level, BlockPos above, double height, String alias,
                                         String template, Map<String, BlockPos> links, float yawDeg) {
        JsonObject t;
        try {
            t = JsonParser.parseString(template).getAsJsonObject();
        } catch (Exception e) {
            return new Created(null, null, "The hologram template is damaged: " + e.getMessage());
        }
        return create(owner, level, above, height, alias, t, "inline", links, yawDeg);
    }

    /** Same, from a template in config/advancedinteractions/templates by its id. */
    public static Created createFromTemplate(ServerPlayer owner, ServerLevel level, BlockPos above, double height, String alias,
                                             String templateId, Map<String, BlockPos> links) {
        return createFromTemplate(owner, level, above, height, alias, templateId, links, Float.NaN);
    }

    public static Created createFromTemplate(ServerPlayer owner, ServerLevel level, BlockPos above, double height, String alias,
                                             String templateId, Map<String, BlockPos> links, float yawDeg) {
        JsonObject t = TemplateManager.json(templateId);
        if (t == null) return new Created(null, null, "There's no hologram template named '" + templateId + "'.");
        return create(owner, level, above, height, alias, t, templateId, links, yawDeg);
    }

    /**
     * Takes over a hologram already linked to this block under {@code linkName} (for example one copied
     * from another server's save) instead of making a new one. It must belong to {@code owner} or to
     * nobody (then it becomes theirs). It is marked as made by the calling mod, like a new one.
     * @return its id, or null when there is none to take over
     */
    public static UUID adopt(ServerPlayer owner, ServerLevel level, BlockPos pos, String linkName, String madeBy) {
        if (owner == null || level == null || pos == null || linkName == null) return null;
        HologramSavedData data = HologramSavedData.get(level);
        for (HologramDef d : data.all()) {
            if (d.owner != null && !d.owner.equals(owner.getUUID())) continue;
            boolean linked = d.links.stream().anyMatch(l -> l.name().equalsIgnoreCase(linkName) && l.pos().equals(pos)
                    && (l.dim() == null || l.dim().equals(level.dimension())));
            if (!linked) continue;
            d.owner = owner.getUUID();
            d.meta.put(PlayerEditing.MADE_BY, madeBy == null || madeBy.isBlank() ? "adopted" : madeBy);
            d.meta.put(PlayerEditing.FIXED_LINKS, linkName);
            data.setDirty();
            C2S_RequestSync.serverPushAll(owner.getServer());
            return d.id;
        }
        return null;
    }

    public static boolean exists(MinecraftServer server, UUID id) {
        return id != null && HologramSavedData.findAnywhere(server, id) != null;
    }

    public static boolean delete(MinecraftServer server, UUID id) {
        var loc = id == null ? null : HologramSavedData.findAnywhere(server, id);
        if (loc == null) return false;
        loc.data().remove(id);
        loc.data().setDirty();
        C2S_RequestSync.serverPushAll(server);
        return true;
    }

    private static Created create(ServerPlayer owner, ServerLevel level, BlockPos above, double height, String wantedAlias,
                                  JsonObject template, String templateId, Map<String, BlockPos> links, float yawDeg) {
        if (owner == null || level == null || above == null) return new Created(null, null, "Missing player or position.");
        if (!PlayerEditing.enabled()) return new Created(null, null, "Player holograms are turned off on this server.");
        if (!TemplateManager.isSnapshotJson(template)) return new Created(null, null, "The template must be a snapshot template.");
        String why = Ownership.placeProblem(owner, level, above.above());
        if (why != null) return new Created(null, null, why);

        HologramSavedData data = HologramSavedData.get(level);
        String base = (wantedAlias == null || wantedAlias.isBlank() ? "holo" : wantedAlias).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.\\-+]", "_");
        if (base.length() > 40) base = base.substring(0, 40);
        String alias = base;
        for (int n = 2; data.isAliasTaken(alias, null); n++) alias = base + "_" + n;

        HologramDef target = new HologramDef();
        target.alias = alias;
        target.dim = level.dimension();
        target.x = above.getX() + 0.5;
        target.y = above.getY() + height;
        target.z = above.getZ() + 0.5;
        target.billboard = Float.isNaN(yawDeg);
        if (!target.billboard) target.fixedYawDeg = yawDeg;
        var built = TemplateManager.buildFromSnapshotJson(template, target, templateId);
        if (built.error() != null) return new Created(null, null, built.error());
        HologramDef d = built.def();
        d.owner = owner.getUUID();
        d.locked = false;
        d.hidden = false;
        d.links.clear();
        if (links != null) links.forEach((name, pos) -> d.links.add(new HologramDef.BlockLink(name, level.dimension(), pos.immutable())));
        d.billboard = target.billboard;
        d.fixedYawDeg = target.fixedYawDeg;
        // Made by a mod: not part of the player's own limit, and its links stay put.
        d.meta.put(PlayerEditing.MADE_BY, templateId == null ? "inline" : templateId);
        if (links != null && !links.isEmpty()) d.meta.put(PlayerEditing.FIXED_LINKS, String.join(",", links.keySet()));
        else d.meta.remove(PlayerEditing.FIXED_LINKS);

        // Same rules as a player saving it in the editor.
        String bad = PlayerEditing.validateNew(owner, d);
        if (bad != null) return new Created(null, null, bad);

        data.put(d);
        data.setDirty();
        C2S_RequestSync.serverPushAll(owner.getServer());
        return new Created(d.id, d.alias, null);
    }
}
