package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.AdvancedInteractions;
import com.luminacity.advancedinteractions.api.Ownership;
import com.luminacity.advancedinteractions.config.AIConfig;
import com.luminacity.advancedinteractions.data.HologramSavedData;
import com.luminacity.advancedinteractions.editor.EditorService;
import com.luminacity.advancedinteractions.editor.PlayerEditing;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Linking a block to a hologram without having to look at it while running a command or clicking a
 * button: the player is put in "pick" mode and the next block they right-click is linked (the click
 * doesn't open the chest or trade at the shop). Also finds the nearest block of a kind.
 */
@Mod.EventBusSubscriber(modid = AdvancedInteractions.MODID)
public final class LinkPicker {
    private LinkPicker() {}

    private static final long PICK_MS = 30_000;
    private record Pending(UUID holo, String name, long until) {}
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** Wait for the player's next right-click on a block and link it as {@code name}. */
    public static void start(ServerPlayer p, HologramDef def, String name) {
        PENDING.put(p.getUUID(), new Pending(def.id, name, System.currentTimeMillis() + PICK_MS));
        p.displayClientMessage(Component.literal("Right-click the block to link as '" + name + "' (30s). Left-click to cancel.")
                .withStyle(ChatFormatting.AQUA), false);
    }

    public static boolean picking(UUID player) { return PENDING.containsKey(player); }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClick(PlayerInteractEvent.RightClickBlock e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer sp)) return;
        Pending pick = PENDING.remove(sp.getUUID());
        if (pick == null) return;
        // The click only picks the block: no chest opening, no shop trade.
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        if (System.currentTimeMillis() > pick.until()) { msg(sp, "Linking timed out; try again.", false); return; }
        String err = link(sp, pick.holo(), pick.name(), (ServerLevel) e.getLevel(), e.getPos().immutable());
        if (err != null) msg(sp, err, false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock e) {
        if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer sp)) return;
        if (PENDING.remove(sp.getUUID()) == null) return;
        e.setCanceled(true);
        msg(sp, "Linking cancelled.", false);
    }

    private static int ticks;

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || ++ticks % 20 != 0 || PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(en -> {
            if (now <= en.getValue().until()) return false;
            ServerPlayer p = e.getServer().getPlayerList().getPlayer(en.getKey());
            if (p != null) msg(p, "Linking timed out.", false);
            return true;
        });
    }

    public static void clearPlayer(UUID player) { PENDING.remove(player); }

    /**
     * Add (or replace) a link, checking who may do it.
     * @return null when linked, otherwise the reason
     */
    public static String link(ServerPlayer p, UUID holoId, String name, ServerLevel level, BlockPos pos) {
        var loc = HologramSavedData.findAnywhere(p.getServer(), holoId);
        if (loc == null) return "That hologram no longer exists.";
        HologramDef def = loc.def();
        boolean staff = EditorService.canEdit(p);
        if (!staff) {
            if (!PlayerEditing.mayEdit(p, def)) return "You can only link blocks to your own holograms.";
            double max = AIConfig.get(AIConfig.PLAYER_MAX_LINK_DISTANCE, 6.0);
            if (!level.dimension().equals(def.dim) || Math.sqrt(pos.distToCenterSqr(def.x, def.y, def.z)) > max + 0.5) {
                return "That block is too far from the hologram (max " + max + " blocks).";
            }
            String why = Ownership.placeProblem(p, level, pos);
            if (why != null) return "You can't link to that block.";
        }
        def.links.removeIf(l -> l.name().equalsIgnoreCase(name));
        def.links.add(new HologramDef.BlockLink(name, level.dimension(), pos));
        loc.data().setDirty();
        C2S_RequestSync.serverPushAll(p.getServer());
        ActionLog.log(p, def, "", "link " + name + " " + pos.toShortString(), "ok");
        String what = level.getBlockState(pos).getBlock().getName().getString();
        msg(p, "Linked '" + name + "' to the " + what + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ".", true);
        return null;
    }

    // ---------------------------------------------------------------- nearest block of a kind

    /** Kinds for "nearest": container, furnace, sign, shop (a sign or container), any (any block entity). */
    public static BlockPos nearest(ServerLevel level, double x, double y, double z, String kind, int radius) {
        String k = kind == null ? "any" : kind.toLowerCase(Locale.ROOT);
        BlockPos center = new BlockPos(x, y, z);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (!level.isLoaded(pos)) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || !matches(be, k)) continue;
            double d = pos.distToCenterSqr(x, y, z);
            if (d < bestD) { bestD = d; best = pos.immutable(); }
        }
        return best;
    }

    private static boolean matches(BlockEntity be, String kind) {
        boolean container = be instanceof Container || be.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent();
        return switch (kind) {
            case "container", "chest" -> container && !(be instanceof AbstractFurnaceBlockEntity);
            case "furnace" -> be instanceof AbstractFurnaceBlockEntity;
            case "sign" -> be instanceof SignBlockEntity;
            case "shop" -> be.getPersistentData().contains("luminshop"); // a LuminShops sign or chest shop
            default -> true;
        };
    }

    private static void msg(ServerPlayer p, String s, boolean ok) {
        p.displayClientMessage(Component.literal(s).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED), false);
    }
}
