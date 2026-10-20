
package com.luminacity.advancedinteractions;

import com.luminacity.advancedinteractions.api.ActionRegistry;
import com.luminacity.advancedinteractions.api.Actions;
import com.luminacity.advancedinteractions.api.DataProviders;
import com.luminacity.advancedinteractions.api.IHologramView;
import com.luminacity.advancedinteractions.hologram.HoloMenuState;
import com.luminacity.advancedinteractions.hologram.HoloPageState;
import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.commands.HologramCommands;
import com.luminacity.advancedinteractions.commands.args.ModArgumentTypes;
import com.luminacity.advancedinteractions.network.NetworkHandler;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import com.luminacity.advancedinteractions.providers.DataPlaceholderProvider;
import com.luminacity.advancedinteractions.providers.HoloPlaceholderProvider;
import com.luminacity.advancedinteractions.providers.MetaPlaceholderProvider;
import com.luminacity.advancedinteractions.providers.PlayerPlaceholderProvider;
import com.luminacity.advancedinteractions.providers.PosPlaceholderProvider;
import com.luminacity.advancedinteractions.providers.ServerPlaceholderProvider;
import com.luminacity.advancedinteractions.providers.TimePlaceholderProvider;
import com.luminacity.advancedinteractions.providers.TopPlaceholderProvider;
import com.luminacity.advancedinteractions.providers.WorldPlaceholderProvider;
import com.luminacity.advancedinteractions.state.PlayerState;
import com.luminacity.advancedinteractions.templates.TemplateManager;
import com.luminacity.advancedinteractions.util.PermUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(AdvancedInteractions.MODID)
public class AdvancedInteractions {
    public static final String MODID = "advancedinteractions";

    private static final AtomicBoolean LUCKPERMS_HOOKED = new AtomicBoolean(false);

    public AdvancedInteractions() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.SERVER, com.luminacity.advancedinteractions.config.AIConfig.SPEC);
        ModArgumentTypes.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(com.luminacity.advancedinteractions.api.PlaceholderIMC::process);
        modBus.addListener(com.luminacity.advancedinteractions.api.Economy::process);
        // Other Forge bus listeners are in events/* via @EventBusSubscriber
        MinecraftForge.EVENT_BUS.register(new HologramCommands()); // harmless; commands are registered via CommandEvents
        // LuckPerms' API is only available once the server has started; retry the hook there.
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void onServerStarted(final ServerStartedEvent evt) {
        hookLuckPerms();
        TemplateManager.reload();
    }

    /**
     * LuckPerms hook: when permissions change (added OR removed), push an updated
     * per-viewer snapshot so overlay views appear/disappear immediately.
     * Safe to call repeatedly; registers at most once.
     */
    private static void hookLuckPerms() {
        if (LUCKPERMS_HOOKED.get()) return;
        // LuckPerms can fire events async, so bounce to the MC server thread.
        boolean ok = PermUtil.registerRecalcListener((uuid) -> {
            try {
                net.minecraft.server.MinecraftServer srv = ServerLifecycleHooks.getCurrentServer();
                if (srv == null) return;
                srv.execute(() -> {
                    try {
                        ServerPlayer p = srv.getPlayerList().getPlayer(uuid);
                        if (p == null) return;
                        // 1) Immediate server-side push (best case)
                        C2S_RequestSync.serverPushForPlayer(p);

                        // 2) Reliable fallback: tell the client to request a full resync right now.
                        // This avoids the "fly away and back" issue caused by movement-based autosync.
                        NetworkHandler.sendTo(p, new com.luminacity.advancedinteractions.network.s2c.S2C_ForceResync());
                    } catch (Throwable ignored2) {}
                });
            } catch (Throwable ignored) {}
        });
        if (ok) LUCKPERMS_HOOKED.set(true);
    }

    private void commonSetup(final FMLCommonSetupEvent evt) {
        NetworkHandler.register();

        // Usually too early for LuckPerms (retried on ServerStartedEvent), but harmless to try.
        hookLuckPerms();

        // Built-in placeholder namespaces
        Placeholders.register("meta", new MetaPlaceholderProvider());
        Placeholders.register("player", new PlayerPlaceholderProvider());
        Placeholders.register("holo", new HoloPlaceholderProvider());
        Placeholders.register("pos", new PosPlaceholderProvider());
        Placeholders.register("server", new ServerPlaceholderProvider());
        Placeholders.register("world", new WorldPlaceholderProvider());
        Placeholders.register("time", new TimePlaceholderProvider());
        Placeholders.register("top", new TopPlaceholderProvider());
        Placeholders.register("state", new com.luminacity.advancedinteractions.providers.StatePlaceholderProvider());
        Placeholders.register("lang", new com.luminacity.advancedinteractions.providers.LangPlaceholderProvider());
        Placeholders.register("chart", new com.luminacity.advancedinteractions.interaction.ChartSampler.Provider());
        // {ftbquests.*} / {ftbteams.*} when those mods are installed.
        com.luminacity.advancedinteractions.integration.FtbIntegration.register();
        // Default {econ.*}: money from whichever economy mod is connected (Economy API). An economy mod that
        // registers its own "econ" (EconomyCore, old name "econ_backend") replaces this.
        Placeholders.registerFallback("econ", new com.luminacity.advancedinteractions.providers.EconPlaceholderProvider());
        // Template variables / data bindings: {data.key} and the bare {key} shorthand
        Placeholders.register("data", new DataPlaceholderProvider());
        // Input fields ({input.qty}) and variables ({var.coins})
        Placeholders.register("input", new com.luminacity.advancedinteractions.interaction.InputValues.Provider());
        Placeholders.register("var", new com.luminacity.advancedinteractions.state.Vars.Provider());
        // Block links ({link.container.count:chest}) and the data source registry
        com.luminacity.advancedinteractions.interaction.BlockLinks.registerBuiltins();
        // Shops: advholo:trade and the built-in "basic" admin shop
        com.luminacity.advancedinteractions.interaction.Trading.register();
        // Binding "namespace:args" (e.g. "estate:testprop") exposes that placeholder namespace as data.
        DataProviders.setFallback(new DataPlaceholderProvider.NamespaceBridge());

        // Client resolver hook for server-only placeholder values
        Placeholders.setRemoteResolver(com.luminacity.advancedinteractions.client.ClientPlaceholderCache::resolve);

        // Built-in page/menu navigation actions (server-driven per-player state)
        Actions.registerPrefix("advholo", (id, params, ctx) -> {
            if (id == null) return;
            String key = id.toLowerCase();
            switch (key) {
                case "page_next" -> {
                    HoloMenuState.clear(ctx.player(), ctx.holo());
                    int cur = HoloPageState.get(ctx.player(), ctx.holo());
                    int next = ctx.holo().nextAccessiblePage(ctx.player(), cur);
                    HoloPageState.set(ctx.player(), ctx.holo(), next);
                    C2S_RequestSync.syncOne(ctx.player(), ctx.holo());
                }
                case "page_prev" -> {
                    HoloMenuState.clear(ctx.player(), ctx.holo());
                    int cur = HoloPageState.get(ctx.player(), ctx.holo());
                    int prev = ctx.holo().prevAccessiblePage(ctx.player(), cur);
                    HoloPageState.set(ctx.player(), ctx.holo(), prev);
                    C2S_RequestSync.syncOne(ctx.player(), ctx.holo());
                }
                case "page_set" -> {
                    String v = params == null ? null : (params.getOrDefault("page", params.get("p")));
                    if (v == null) v = params == null ? null : params.get("i");
                    if (v == null) return;
                    try {
                        int idx1 = Integer.parseInt(v.trim());
                        int idx0 = Math.max(0, idx1 - 1); // user-facing is 1-based
                        int coerced = ctx.holo().coercePageFor(ctx.player(), idx0);
                        HoloMenuState.clear(ctx.player(), ctx.holo());
                        HoloPageState.set(ctx.player(), ctx.holo(), coerced);
                        C2S_RequestSync.syncOne(ctx.player(), ctx.holo());
                    } catch (NumberFormatException ignored) {}
                }
                // Menus: named widget sets that replace the page content for this player.
                //   advholo:menu_open?name=shop   advholo:menu_toggle?name=shop   advholo:menu_close
                case "menu_open", "menu_toggle" -> {
                    String name = params == null ? null : params.getOrDefault("name", params.get("menu"));
                    if (name == null || name.isBlank()) return;
                    boolean isOpen = name.equalsIgnoreCase(HoloMenuState.get(ctx.player(), ctx.holo()));
                    if (key.equals("menu_toggle") && isOpen) HoloMenuState.clear(ctx.player(), ctx.holo());
                    else HoloMenuState.set(ctx.player(), ctx.holo(), name);
                    C2S_RequestSync.syncOne(ctx.player(), ctx.holo());
                }
                case "menu_close", "menu_back", "menu_main" -> {
                    HoloMenuState.clear(ctx.player(), ctx.holo());
                    C2S_RequestSync.syncOne(ctx.player(), ctx.holo());
                }
                // Show / hide elements for this player (e.g. ones set to "start hidden"):
                //   advholo:show?id=details   advholo:hide?id=a,b   advholo:toggle_show?id=details   (&seconds=5 reverts)
                case "show", "hide", "toggle_show" -> {
                    String ids = params == null ? null : params.getOrDefault("id", params.get("widget"));
                    if (ids == null || ids.isBlank() || ctx.holo() == null) return;
                    double secs;
                    try { secs = Double.parseDouble(params.getOrDefault("seconds", "0")); } catch (NumberFormatException e) { secs = 0; }
                    for (String wid : ids.split(",")) {
                        String w = wid.trim();
                        if (w.isEmpty()) continue;
                        boolean visible = switch (key) {
                            case "show" -> true;
                            case "hide" -> false;
                            default -> {
                                var widget = ctx.holo().findWidgetAnywhere(w);
                                yield widget == null || !com.luminacity.advancedinteractions.interaction.WidgetRules.visibleByToggle(ctx.player(), ctx.holo(), widget);
                            }
                        };
                        com.luminacity.advancedinteractions.interaction.WidgetRules.setVisible(ctx.player(), ctx.holo(), w, visible, secs);
                    }
                    C2S_RequestSync.syncOne(ctx.player(), ctx.holo());
                }
                // Variables ({var.key}): per player or global, saved unless temp=true.
                //   advholo:var_set?key=coins&value=5&scope=player|global&temp=false
                //   advholo:var_add?key=coins&amount=1   advholo:var_toggle?key=lights   advholo:var_clear?key=coins
                case "var_set", "var_add", "var_toggle", "var_clear" -> {
                    String k = params == null ? null : params.get("key");
                    if (k == null || k.isBlank()) return;
                    boolean global = "global".equalsIgnoreCase(params.getOrDefault("scope", "player"));
                    boolean temp = "true".equalsIgnoreCase(params.getOrDefault("temp", "false"));
                    var server = ctx.player().getServer();
                    java.util.UUID who = global ? null : ctx.player().getUUID();
                    String cur = global ? com.luminacity.advancedinteractions.state.Vars.global(server, k)
                            : com.luminacity.advancedinteractions.state.Vars.player(server, who, k);
                    String next = switch (key) {
                        case "var_set" -> params.getOrDefault("value", "");
                        case "var_toggle" -> Boolean.toString(!"true".equalsIgnoreCase(cur));
                        case "var_add" -> {
                            double c, amt;
                            try { c = Double.parseDouble(cur == null || cur.isBlank() ? "0" : cur); } catch (NumberFormatException e) { c = 0; }
                            try { amt = Double.parseDouble(params.getOrDefault("amount", "1")); } catch (NumberFormatException e) { amt = 1; }
                            double v = c + amt;
                            yield v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
                        }
                        default -> null;
                    };
                    com.luminacity.advancedinteractions.state.Vars.set(server, who, k, next, temp);
                    if (ctx.holo() != null) C2S_RequestSync.syncOne(ctx.player(), ctx.holo());
                }
                // Buy / sell through a shop: advholo:trade?side=buy&qty={input.qty}&shop=basic&item=minecraft:diamond&price=10
                case "trade" -> com.luminacity.advancedinteractions.interaction.Trading.run(ctx.player(), ctx.holo(), params);
                // Server event flags for schedules: advholo:flag?name=sale&on=true|false|toggle
                case "flag" -> {
                    String name = params == null ? null : params.get("name");
                    if (name == null || name.isBlank()) return;
                    String on = params.getOrDefault("on", "true");
                    boolean v = on.equalsIgnoreCase("toggle") ? !com.luminacity.advancedinteractions.api.Flags.get(name) : "true".equalsIgnoreCase(on);
                    com.luminacity.advancedinteractions.api.Flags.set(name, v);
                }
                // Tell other mods/scripts something happened: advholo:event?name=opened_shop&any=params
                // (posts HologramActionEvent on the Forge event bus).
                case "event" -> {
                    String name = params == null ? null : params.get("name");
                    if (name == null || name.isBlank()) return;
                    net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new com.luminacity.advancedinteractions.api.event.HologramActionEvent(
                            ctx.player(), ctx.holo() == null ? null : ctx.holo().id, name, params));
                }
                // Per-player variables ({state.key}): a toggle stays on for that player.
                //   advholo:state_set?key=music&value=on   advholo:state_toggle?key=music
                //   advholo:state_add?key=clicks&amount=1   advholo:state_clear?key=music
                case "state_set", "state_toggle", "state_add", "state_clear" -> {
                    String k = params == null ? null : params.get("key");
                    if (k == null || k.isBlank()) return;
                    PlayerState st = PlayerState.get(ctx.player().getServer());
                    java.util.UUID uuid = ctx.player().getUUID();
                    switch (key) {
                        case "state_set" -> st.set(uuid, k, params.getOrDefault("value", ""));
                        case "state_toggle" -> st.set(uuid, k, Boolean.toString(!"true".equalsIgnoreCase(st.get(uuid, k))));
                        case "state_add" -> {
                            double cur, amt;
                            try { cur = Double.parseDouble(st.get(uuid, k) == null ? "0" : st.get(uuid, k)); } catch (NumberFormatException e) { cur = 0; }
                            try { amt = Double.parseDouble(params.getOrDefault("amount", "1")); } catch (NumberFormatException e) { amt = 1; }
                            double v = cur + amt;
                            st.set(uuid, k, v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v));
                        }
                        default -> st.remove(uuid, k);
                    }
                    if (ctx.holo() != null) C2S_RequestSync.syncOne(ctx.player(), ctx.holo());
                }
                // Feedback for proximity triggers and chained effects:
                //   advholo:sound?id=minecraft:entity.experience_orb.pickup&volume=1&pitch=1
                //   advholo:message?text=§aWelcome!      advholo:actionbar?text=§eHello
                case "sound" -> {
                    if (params == null || params.get("id") == null) return;
                    var rl = net.minecraft.resources.ResourceLocation.tryParse(params.get("id").trim());
                    var sound = rl == null ? null : net.minecraftforge.registries.ForgeRegistries.SOUND_EVENTS.getValue(rl);
                    if (sound == null) return;
                    float vol = parseFloat(params.get("volume"), 1f), pitch = parseFloat(params.get("pitch"), 1f);
                    ctx.player().playNotifySound(sound, net.minecraft.sounds.SoundSource.MASTER, vol, pitch);
                }
                case "message", "actionbar" -> {
                    String text = params == null ? null : params.get("text");
                    if (text == null || text.isBlank()) return;
                    ctx.player().displayClientMessage(com.luminacity.advancedinteractions.util.TextFmt.component(text), key.equals("actionbar"));
                }
            }
        });

        // Demo actions (optional sanity checks)
        ActionRegistry.register("demo:hello", (ServerPlayer player, IHologramView view, String params) -> {
            player.displayClientMessage(
                Component.literal("Hello from AdvancedInteractions at ["
                        + (int)view.x() + "," + (int)view.y() + "," + (int)view.z() + "]"),
                false
            );
        });
        // demo:elevator_call?y=70  -> moves the player to that Y in the same column if the spot is safe.
        ActionRegistry.register("demo:elevator_call", (ServerPlayer player, IHologramView view, String params) -> {
            Map<String, String> q = parseParams(params);
            String ys = q.getOrDefault("y", q.getOrDefault("floor", q.get("")));
            if (ys == null) {
                player.displayClientMessage(Component.literal("Elevator: no target floor (use demo:elevator_call?y=<Y>)."), true);
                return;
            }
            int ty;
            try { ty = (int) Math.floor(Double.parseDouble(ys.trim())); } catch (NumberFormatException e) {
                player.displayClientMessage(Component.literal("Elevator: bad floor '" + ys + "'."), true);
                return;
            }
            ServerLevel lvl = player.getLevel();
            if (Math.abs(ty - player.getBlockY()) > 128 || ty <= lvl.getMinBuildHeight() || ty >= lvl.getMaxBuildHeight() - 1) {
                player.displayClientMessage(Component.literal("Elevator: floor out of range."), true);
                return;
            }
            BlockPos feet = new BlockPos(player.getBlockX(), ty, player.getBlockZ());
            boolean clear = !lvl.getBlockState(feet).getMaterial().blocksMotion()
                    && !lvl.getBlockState(feet.above()).getMaterial().blocksMotion();
            boolean floor = lvl.getBlockState(feet.below()).getMaterial().blocksMotion();
            if (!clear || !floor) {
                player.displayClientMessage(Component.literal("Elevator: that floor is blocked."), true);
                return;
            }
            player.teleportTo(player.getX(), ty, player.getZ());
            player.displayClientMessage(Component.literal("Arrived at floor Y=" + ty + "."), true);
        });
        // demo:door_toggle?x=..&y=..&z=..  (or "x,y,z"; default: nearest door/trapdoor/gate near the hologram)
        ActionRegistry.register("demo:door_toggle", (ServerPlayer player, IHologramView view, String params) -> {
            ServerLevel lvl = player.getLevel();
            Map<String, String> q = parseParams(params);
            BlockPos target = null;
            try {
                if (q.containsKey("x") && q.containsKey("y") && q.containsKey("z")) {
                    target = new BlockPos(Integer.parseInt(q.get("x").trim()), Integer.parseInt(q.get("y").trim()), Integer.parseInt(q.get("z").trim()));
                } else if (q.containsKey("")) {
                    String[] p = q.get("").split("[,\\s]+");
                    if (p.length == 3) target = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                }
            } catch (NumberFormatException ignored) {}

            BlockPos origin = view.anchorBlock() != null ? view.anchorBlock() : player.blockPosition();
            if (target == null) {
                double best = Double.MAX_VALUE;
                for (BlockPos p : BlockPos.betweenClosed(origin.offset(-4, -4, -4), origin.offset(4, 4, 4))) {
                    if (!lvl.getBlockState(p).hasProperty(BlockStateProperties.OPEN)) continue;
                    double d = p.distSqr(origin);
                    if (d < best) { best = d; target = p.immutable(); }
                }
            }
            if (target == null || target.distSqr(origin) > 16 * 16) {
                player.displayClientMessage(Component.literal("Door: nothing to toggle near this hologram."), true);
                return;
            }
            BlockState st = lvl.getBlockState(target);
            if (!st.hasProperty(BlockStateProperties.OPEN)) {
                player.displayClientMessage(Component.literal("Door: target block can't open."), true);
                return;
            }
            boolean open = !st.getValue(BlockStateProperties.OPEN);
            if (st.getBlock() instanceof DoorBlock door) {
                door.setOpen(player, lvl, st, target, open); // handles both halves + sound
            } else {
                lvl.setBlock(target, st.setValue(BlockStateProperties.OPEN, open), 10);
            }
            player.displayClientMessage(Component.literal(open ? "Opened." : "Closed."), true);
        });
    }

    private static float parseFloat(String s, float def) {
        try { return s == null ? def : Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** "x=1&y=2" -> {x:1, y:2}; a bare value ("70" / "1,64,2") is stored under "". */
    private static Map<String, String> parseParams(String params) {
        Map<String, String> m = new HashMap<>();
        if (params == null || params.isBlank()) return m;
        for (String pair : com.luminacity.advancedinteractions.util.QueryParams.split(params)) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) m.put("", pair);
            else m.put(pair.substring(0, eq).trim().toLowerCase(), pair.substring(eq + 1));
        }
        return m;
    }
}
