package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.api.ActionRegistry;
import com.luminacity.advancedinteractions.api.Actions;
import com.luminacity.advancedinteractions.api.Economy;
import com.luminacity.advancedinteractions.api.IHologramView;
import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.panel.ButtonWidget;
import com.luminacity.advancedinteractions.panel.ClickSettings;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import com.luminacity.advancedinteractions.api.event.HologramClickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs hologram actions on the server for clicks and proximity triggers: console rules, placeholders,
 * cooldowns and costs all live here so every trigger obeys the same rules.
 */
public final class ActionRunner {
    private ActionRunner() {}

    /** "holoId|widgetId|playerId-or-*" -> server tick the cooldown ends. */
    private static final Map<String, Long> COOLDOWNS = new ConcurrentHashMap<>();

    /**
     * Run a stored (unresolved) action for a player.
     *
     * @param snap the viewer's snapshot of the hologram (live position for entity-attached ones)
     * @return false if the action was blocked or nothing handled it
     */
    public static boolean run(ServerPlayer player, ServerLevel lvl, HologramDef def, HologramDef snap, String rawAction) {
        if (rawAction == null || rawAction.isBlank()) return false;

        // Console mode is decided by the stored action, before placeholders resolve, so a value
        // that resolves to "console:..." can never escalate. Console actions only take safe tokens.
        boolean console = ActionRegistry.isConsoleAction(rawAction);
        if (console && ActionRegistry.unsafeConsoleToken(rawAction) != null) {
            fail(player, "This console action is blocked (unsafe placeholder).");
            return false;
        }

        HologramDef at = snap == null ? def : snap;
        IHologramView view = new IHologramView() {
            @Override public UUID id() { return def.id; }
            @Override public ResourceKey<Level> dimension() { return at.dim; }
            @Override public double x() { return at.x; }
            @Override public double y() { return at.y; }
            @Override public double z() { return at.z; }
            @Override public BlockPos anchorBlock() { return new BlockPos(at.x, at.y, at.z); }
        };

        String resolved = Placeholders.apply(rawAction, new Placeholders.PHContext(lvl, player, def));
        // Params are taken from the action id itself ("ns:id?k=v"); client-sent params are not trusted.
        Actions.takeFailure();
        boolean ok = Actions.dispatch(resolved, player, lvl, def, view, "", console);
        boolean refused = Actions.takeFailure(); // the handler reported it didn't go through (it told the player why)
        if (!ok) fail(player, "Action failed: " + resolved);
        return ok && !refused;
    }

    /** A button click: pick the action for the click type, then cooldown, cost, run (refund if it can't run). */
    public static void clickButton(ServerPlayer player, ServerLevel lvl, HologramDef def, HologramDef snap,
                                   ButtonWidget bw, ClickSettings.Type type) {
        clickButton(player, lvl, def, snap, bw, type, null);
    }

    /** @param hitPoint where the click landed (for particles), or null */
    public static void clickButton(ServerPlayer player, ServerLevel lvl, HologramDef def, HologramDef snap,
                                   ButtonWidget bw, ClickSettings.Type type, net.minecraft.world.phys.Vec3 hitPoint) {
        clickWidget(player, lvl, def, snap, bw, type, hitPoint);
    }

    /**
     * A click on any clickable element (button, or text/shape/image/chart with clicking turned on).
     * Checks use rules, disabled state, cooldown and cost, asks for confirmation if set, posts
     * {@link HologramClickEvent}, then runs the action and its chain.
     */
    public static void clickWidget(ServerPlayer player, ServerLevel lvl, HologramDef def, HologramDef snap,
                                   PanelWidget w, ClickSettings.Type type, net.minecraft.world.phys.Vec3 hitPoint) {
        clickWidget(player, lvl, def, snap, w, type, hitPoint, false);
    }

    private static void clickWidget(ServerPlayer player, ServerLevel lvl, HologramDef def, HologramDef snap,
                                    PanelWidget w, ClickSettings.Type type, net.minecraft.world.phys.Vec3 hitPoint, boolean confirmed) {
        ClickSettings c = w.interaction();
        if (c == null || !ClickSettings.isClickable(w)) return;
        String action = c.actionFor(type, ClickSettings.primaryAction(w));
        if (action == null) action = "";
        if (action.isBlank() && c.chain.isEmpty()) return;

        if (!WidgetRules.canUse(player, def, w, lvl)) { fail(player, "You can't use that."); sound(player, c.errorSound, c); return; }
        if (WidgetRules.isDisabled(player, def, w, lvl)) { fail(player, "That's not available right now."); sound(player, c.errorSound, c); return; }

        long now = player.getServer().getTickCount();
        String cdKey = def.id + "|" + w.id() + "|" + (c.cooldownGlobal ? "*" : player.getUUID().toString());
        if (c.cooldownTicks > 0) {
            Long until = COOLDOWNS.get(cdKey);
            if (until != null && until > now) {
                fail(player, String.format(java.util.Locale.ROOT, "Wait %.1fs before using this again.", (until - now) / 20.0));
                sound(player, c.errorSound, c);
                return;
            }
        }
        if (c.hasCost()) {
            String problem = costProblem(player, c);
            if (problem != null) { fail(player, problem); sound(player, c.errorSound, c); return; }
        }

        // "Are you sure?" first; everything above runs again when they answer yes.
        if (!confirmed && !c.confirm.isBlank()) {
            askConfirm(player, def, w, type, hitPoint, c.confirm);
            return;
        }

        // Other mods may veto or change the click.
        HologramClickEvent ev = postClick(player, def, w.id(), type, action);
        if (ev.isCanceled()) {
            if (!ev.getMessage().isBlank()) fail(player, ev.getMessage());
            sound(player, c.errorSound, c);
            ActionLog.log(player, def, w.id(), action, "cancelled");
            return;
        }
        action = ev.getActionId();

        if (c.hasCost()) takeCost(player, c);
        if (c.cooldownTicks > 0) {
            COOLDOWNS.put(cdKey, now + c.cooldownTicks);
            if (COOLDOWNS.size() > 4096) COOLDOWNS.values().removeIf(t -> t <= now);
        }

        CURRENT_ELEMENT.set(w.id());
        boolean ok;
        try {
            ok = action.isBlank() || run(player, lvl, def, snap, action);
        } finally {
            CURRENT_ELEMENT.remove();
        }
        ActionLog.log(player, def, w.id(), action, ok ? "ok" : "failed");
        runChain(player, def, c.chain, ok);
        if (!ok) {
            if (c.hasCost()) refund(player, c);
            COOLDOWNS.remove(cdKey);
            sound(player, c.errorSound, c);
            return;
        }
        sound(player, c.successSound, c);
        spawnParticles(player, lvl, c, hitPoint);
    }

    private static final ThreadLocal<String> CURRENT_ELEMENT = new ThreadLocal<>();

    /** Id of the element whose click is running right now (for actions like trades), or "". */
    public static String currentElement() {
        String s = CURRENT_ELEMENT.get();
        return s == null ? "" : s;
    }

    static HologramClickEvent postClick(ServerPlayer player, HologramDef def, String elementId, ClickSettings.Type type, String action) {
        HologramDef.BlockLink link = def.links == null || def.links.isEmpty() ? null : def.links.get(0);
        java.util.Map<String, String> vars = new java.util.HashMap<>(
                com.luminacity.advancedinteractions.state.PlayerState.get(player.getServer()).all(player.getUUID()));
        vars.putAll(InputValues.all(player.getUUID(), def.id));
        HologramClickEvent ev = new HologramClickEvent(player, def.id, def.alias, elementId,
                HologramClickEvent.ClickType.valueOf(type.name()), action,
                link == null ? null : link.dim(), link == null ? null : link.pos(), vars);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(ev);
        return ev;
    }

    // ---------------------------------------------------------------- action chains

    /**
     * Follow-up steps after the main action: each runs if its "when" matches the main result, after
     * waiting its delay (delays add up, so steps play in order).
     */
    private static void runChain(ServerPlayer player, HologramDef def, java.util.List<ClickSettings.Step> chain, boolean ok) {
        if (chain == null || chain.isEmpty()) return;
        UUID uuid = player.getUUID();
        UUID holo = def.id;
        double wait = 0;
        for (ClickSettings.Step st : chain) {
            if (st.action == null || st.action.isBlank()) continue;
            boolean match = st.when == ClickSettings.Step.When.ALWAYS
                    || (st.when == ClickSettings.Step.When.SUCCESS) == ok;
            if (!match) continue;
            wait += Math.max(0, st.delay);
            String act = st.action;
            if (wait <= 0) {
                run(player, player.getLevel(), def, com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync.prepareForViewer(def, player, player.getLevel()), act);
                continue;
            }
            TaskScheduler.later((int) Math.round(wait * 20), server -> {
                ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                if (p == null) return;
                var loc = com.luminacity.advancedinteractions.data.HologramSavedData.findAnywhere(server, holo);
                if (loc == null) return;
                ServerLevel l = p.getLevel();
                run(p, l, loc.def(), com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync.prepareForViewer(loc.def(), p, l), act);
            });
        }
    }

    // ---------------------------------------------------------------- confirmation

    private record Pending(int token, UUID holo, String widget, ClickSettings.Type type, net.minecraft.world.phys.Vec3 hit, long expires) {}
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final java.util.Random TOKENS = new java.util.Random();

    private static void askConfirm(ServerPlayer player, HologramDef def, PanelWidget w, ClickSettings.Type type,
                                   net.minecraft.world.phys.Vec3 hit, String question) {
        int token = TOKENS.nextInt(Integer.MAX_VALUE);
        PENDING.put(player.getUUID(), new Pending(token, def.id, w.id(), type, hit, System.currentTimeMillis() + 60_000));
        String text = Placeholders.apply(question, new Placeholders.PHContext(player.getLevel(), player, def));
        com.luminacity.advancedinteractions.network.NetworkHandler.sendTo(player,
                new com.luminacity.advancedinteractions.network.ConfirmPackets.Ask(token, "Are you sure?", text));
    }

    /** The player's answer to a confirmation; re-validates everything before running. */
    public static void confirmed(ServerPlayer player, int token, boolean yes) {
        Pending p = PENDING.remove(player.getUUID());
        if (p == null || p.token() != token || !yes || System.currentTimeMillis() > p.expires()) return;
        var loc = com.luminacity.advancedinteractions.data.HologramSavedData.findAnywhere(player.getServer(), p.holo());
        if (loc == null) return;
        ServerLevel lvl = player.getLevel();
        HologramDef def = loc.def();
        HologramDef snap = com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync.prepareForViewer(def, player, lvl);
        if (snap == null || (snap.dim != null && !snap.dim.equals(lvl.dimension()))) return;
        // Still close enough (they may have walked off while the popup was open).
        double reach = WidgetRules.clickRange(def) + com.luminacity.advancedinteractions.network.c2s.C2S_HologramClick.panelHalfExtent(snap) + 3;
        if (player.getEyePosition().distanceTo(new net.minecraft.world.phys.Vec3(snap.x, snap.y, snap.z)) > reach) {
            fail(player, "You walked too far away.");
            return;
        }
        for (PanelWidget w : snap.widgets) {
            if (p.widget().equals(w.id())) { clickWidget(player, lvl, def, snap, w, p.type(), p.hit(), true); return; }
        }
    }

    public static void clearPlayer(UUID player) { PENDING.remove(player); }

    /** A click on the panel itself (meta "click.action"). */
    public static void clickPanel(ServerPlayer player, ServerLevel lvl, HologramDef def, HologramDef snap, String action) {
        if (action == null || action.isBlank()) return;
        HologramClickEvent ev = postClick(player, def, "__panel__", ClickSettings.Type.PRIMARY, action);
        if (ev.isCanceled()) {
            if (!ev.getMessage().isBlank()) fail(player, ev.getMessage());
            return;
        }
        boolean ok = run(player, lvl, def, snap, ev.getActionId());
        ActionLog.log(player, def, "__panel__", ev.getActionId(), ok ? "ok" : "failed");
    }

    /** Play a sound to just this player (no-op for blank/unknown ids). */
    private static void sound(ServerPlayer p, String id, ClickSettings c) {
        if (id == null || id.isBlank()) return;
        ResourceLocation rl = ResourceLocation.tryParse(id.trim().toLowerCase(java.util.Locale.ROOT));
        net.minecraft.sounds.SoundEvent ev = rl == null ? null : net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(rl);
        if (ev != null) p.playNotifySound(ev, net.minecraft.sounds.SoundSource.MASTER, c.soundVolume, 1f);
    }

    /**
     * Click particles, sent by the server so they use the button's real settings and appear only for
     * clicks that went through. "Force" shows them regardless of the player's particle setting.
     */
    private static void spawnParticles(ServerPlayer player, ServerLevel lvl, ClickSettings c, net.minecraft.world.phys.Vec3 at) {
        if (c.particle == null || c.particle.isBlank() || at == null) return;
        ResourceLocation rl = ResourceLocation.tryParse(c.particle.trim().toLowerCase(java.util.Locale.ROOT));
        var type = rl == null ? null : ForgeRegistries.PARTICLE_TYPES.getValue(rl);
        if (!(type instanceof net.minecraft.core.particles.SimpleParticleType simple)) return;
        // Nudge toward the player so the particles sit in front of the panel, not inside it.
        net.minecraft.world.phys.Vec3 toward = player.getEyePosition().subtract(at).normalize().scale(0.15);
        net.minecraft.world.phys.Vec3 p = at.add(toward);
        int n = Math.max(1, Math.min(64, c.particleCount));
        lvl.sendParticles(player, simple, true, p.x, p.y, p.z, n, 0.12, 0.12, 0.12, 0.02);
    }

    // ---------------------------------------------------------------- costs

    private static Item costItem(ClickSettings c) {
        ResourceLocation rl = ResourceLocation.tryParse(c.costItem);
        return rl == null || !ForgeRegistries.ITEMS.containsKey(rl) ? null : ForgeRegistries.ITEMS.getValue(rl);
    }

    /** @return null if the player can pay, otherwise what is missing */
    private static String costProblem(ServerPlayer p, ClickSettings c) {
        if (c.costCount > 0 && !c.costItem.isBlank()) {
            Item item = costItem(c);
            if (item == null) return "This button's cost item '" + c.costItem + "' doesn't exist.";
            if (p.getInventory().countItem(item) < c.costCount) {
                return "You need " + c.costCount + "x " + new ItemStack(item).getHoverName().getString() + ".";
            }
        }
        if (c.costMoney > 0) {
            Economy.Provider eco = Economy.get();
            if (eco == null) return "This button costs money, but no economy mod is installed.";
            if (eco.balance(p) < c.costMoney) return "You need " + eco.format(c.costMoney) + ".";
        }
        return null;
    }

    private static void takeCost(ServerPlayer p, ClickSettings c) {
        if (c.costCount > 0 && !c.costItem.isBlank()) {
            Item item = costItem(c);
            Inventory inv = p.getInventory();
            int remaining = c.costCount;
            for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (!s.is(item)) continue;
                int take = Math.min(remaining, s.getCount());
                s.shrink(take);
                remaining -= take;
            }
            inv.setChanged();
        }
        if (c.costMoney > 0 && Economy.get() != null) Economy.get().withdraw(p, c.costMoney);
    }

    private static void refund(ServerPlayer p, ClickSettings c) {
        if (c.costCount > 0 && !c.costItem.isBlank()) {
            Item item = costItem(c);
            if (item != null) {
                ItemStack back = new ItemStack(item, c.costCount);
                if (!p.getInventory().add(back)) p.drop(back, false);
            }
        }
        if (c.costMoney > 0 && Economy.get() != null) Economy.get().deposit(p, c.costMoney);
    }

    private static void fail(ServerPlayer player, String msg) {
        player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.RED), true);
    }
}
