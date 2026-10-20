package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.api.datasource.DataSource;
import com.luminacity.advancedinteractions.api.datasource.DataSources;
import com.luminacity.advancedinteractions.api.datasource.LinkContext;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.util.ConditionEval;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Holograms linked to blocks (a chest, a furnace, a sign, a shop block...). Values come from data sources:
 * {@code {link.SOURCE.KEY:LINK[,ARG]}}, e.g. {@code {link.container.count:chest}}. A link whose chunk
 * isn't loaded, or whose block is gone, is "unavailable": its values are empty and
 * {@code {link.available:LINK}} is false. Conditions: {@code available(LINK)} and {@code linked(LINK)}.
 */
public final class BlockLinks {
    private BlockLinks() {}

    public static void registerBuiltins() {
        Placeholders.register("link", new Provider());
        DataSources.register("block", new BlockSource());
        DataSources.register("container", new ContainerSource());
        DataSources.register("furnace", new FurnaceSource());
        DataSources.register("sign", new SignSource());
        ConditionEval.register("available", (arg, ctx) -> {
            LinkContext lc = context(ctx, arg.trim());
            return lc != null && !lc.state().isAir();
        });
        ConditionEval.register("linked", (arg, ctx) -> find(ctx.holo(), arg.trim()) != null);
    }

    /** The named link (or the first one for a blank name). */
    public static HologramDef.BlockLink find(HologramDef def, String name) {
        if (def == null || def.links == null || def.links.isEmpty()) return null;
        if (name == null || name.isBlank()) return def.links.get(0);
        for (HologramDef.BlockLink l : def.links) if (l.name().equalsIgnoreCase(name)) return l;
        return null;
    }

    /** A readable context for the link, or null when it is unavailable (missing, other dimension unloaded, chunk not loaded). */
    public static LinkContext context(Placeholders.PHContext ctx, String linkName) {
        if (ctx == null || ctx.holo() == null) return null;
        HologramDef.BlockLink link = find(ctx.holo(), linkName);
        if (link == null) return null;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        ServerLevel level = link.dim() == null ? null : server.getLevel(link.dim());
        if (level == null || !level.isLoaded(link.pos())) return null;
        ServerPlayer viewer = ctx.viewer() instanceof ServerPlayer sp ? sp : null;
        return new LinkContext(level, link.pos(), link.name(), ctx.holo().id, viewer);
    }

    /** {link.SOURCE.KEY:LINK[,ARG]} and {link.available:LINK}. */
    static final class Provider implements Placeholders.PlaceholderProvider {
        @Override
        public String resolve(String key, String args, Placeholders.PHContext ctx) {
            if (key == null) return "";
            String linkName = args == null ? "" : args;
            String arg = "";
            int comma = linkName.indexOf(',');
            if (comma >= 0) { arg = linkName.substring(comma + 1).trim(); linkName = linkName.substring(0, comma).trim(); }
            String k = key.toLowerCase(Locale.ROOT);
            if (k.equals("available")) {
                LinkContext lc = context(ctx, linkName);
                return Boolean.toString(lc != null && !lc.state().isAir());
            }
            if (k.equals("name")) {
                HologramDef.BlockLink l = find(ctx == null ? null : ctx.holo(), linkName);
                return l == null ? "" : l.name();
            }
            int dot = k.indexOf('.');
            if (dot <= 0) return "";
            DataSource src = DataSources.get(k.substring(0, dot));
            if (src == null) return "";
            LinkContext lc = context(ctx, linkName);
            if (lc == null || !src.available(lc)) return "";
            try {
                String v = src.get(k.substring(dot + 1), arg, lc);
                return v == null ? "" : v;
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public Map<String, String> keys() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("available:LINK", "true when the linked block is loaded and present");
            m.put("name:LINK", "The link's name");
            for (String id : DataSources.ids()) {
                DataSource s = DataSources.get(id);
                s.keys().forEach((k, d) -> m.put(id + "." + k + ":LINK", d));
            }
            return m;
        }
    }

    // ---------------------------------------------------------------- built-in sources

    static final class BlockSource implements DataSource {
        @Override
        public String get(String key, String arg, LinkContext ctx) {
            var st = ctx.state();
            return switch (key) {
                case "name" -> st.getBlock().getName().getString();
                case "id" -> String.valueOf(ForgeRegistries.BLOCKS.getKey(st.getBlock()));
                case "powered" -> Integer.toString(ctx.level().getBestNeighborSignal(ctx.pos()));
                case "x" -> Integer.toString(ctx.pos().getX());
                case "y" -> Integer.toString(ctx.pos().getY());
                case "z" -> Integer.toString(ctx.pos().getZ());
                default -> null;
            };
        }

        @Override
        public Map<String, String> keys() {
            return Map.of("name", "Block name", "id", "Block id", "powered", "Redstone signal reaching it (0-15)", "x", "X", "y", "Y", "z", "Z");
        }
    }

    /** Any inventory: vanilla containers and modded blocks exposing an item handler. */
    static final class ContainerSource implements DataSource {
        private static IItemHandler handler(LinkContext ctx) {
            BlockEntity be = ctx.blockEntity();
            if (be == null) return null;
            return be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        }

        @Override
        public boolean available(LinkContext ctx) {
            BlockEntity be = ctx.blockEntity();
            return be instanceof Container || handler(ctx) != null;
        }

        @Override
        public String get(String key, String arg, LinkContext ctx) {
            IItemHandler h = handler(ctx);
            BlockEntity be = ctx.blockEntity();
            int slots = h != null ? h.getSlots() : be instanceof Container c ? c.getContainerSize() : 0;
            Item filter = null;
            if (!arg.isBlank()) {
                ResourceLocation rl = ResourceLocation.tryParse(arg.trim().toLowerCase(Locale.ROOT));
                filter = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
            }
            int count = 0, used = 0;
            Map<Item, Integer> totals = new HashMap<>();
            for (int i = 0; i < slots; i++) {
                ItemStack s = h != null ? h.getStackInSlot(i) : ((Container) be).getItem(i);
                if (s.isEmpty()) continue;
                used++;
                totals.merge(s.getItem(), s.getCount(), Integer::sum);
                if (filter == null || s.is(filter)) count += s.getCount();
            }
            return switch (key) {
                case "count" -> Integer.toString(count);
                case "slots" -> Integer.toString(slots);
                case "used" -> Integer.toString(used);
                case "free" -> Integer.toString(slots - used);
                case "fill" -> Integer.toString(slots == 0 ? 0 : Math.round(used * 100f / slots));
                case "types" -> Integer.toString(totals.size());
                case "has" -> Boolean.toString(count > 0);
                case "top" -> totals.entrySet().stream().max(Map.Entry.comparingByValue())
                        .map(e -> new ItemStack(e.getKey()).getHoverName().getString()).orElse("");
                default -> null;
            };
        }

        @Override
        public Map<String, String> keys() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("count", "Items inside (add ,ITEM to count one item: {link.container.count:chest,minecraft:diamond})");
            m.put("has", "true if it holds any (or the given ,ITEM)");
            m.put("slots", "Number of slots");
            m.put("used", "Slots in use");
            m.put("free", "Empty slots");
            m.put("fill", "Percent of slots used");
            m.put("types", "Different items inside");
            m.put("top", "Name of the item there is most of");
            return m;
        }
    }

    /** Furnaces, smokers and blast furnaces. */
    static final class FurnaceSource implements DataSource {
        @Override
        public boolean available(LinkContext ctx) { return ctx.blockEntity() instanceof AbstractFurnaceBlockEntity; }

        @Override
        public String get(String key, String arg, LinkContext ctx) {
            if (!(ctx.blockEntity() instanceof AbstractFurnaceBlockEntity f)) return null;
            var tag = f.saveWithoutMetadata();
            int burn = tag.getInt("BurnTime"), cook = tag.getInt("CookTime"), total = tag.getInt("CookTimeTotal");
            return switch (key) {
                case "burning" -> Boolean.toString(burn > 0);
                case "progress" -> Integer.toString(total <= 0 ? 0 : Math.round(cook * 100f / total));
                case "burn_seconds" -> Integer.toString(burn / 20);
                case "input" -> name(f.getItem(0));
                case "fuel" -> name(f.getItem(1));
                case "output" -> name(f.getItem(2));
                case "input_count" -> Integer.toString(f.getItem(0).getCount());
                case "fuel_count" -> Integer.toString(f.getItem(1).getCount());
                case "output_count" -> Integer.toString(f.getItem(2).getCount());
                default -> null;
            };
        }

        private static String name(ItemStack s) { return s.isEmpty() ? "" : s.getHoverName().getString(); }

        @Override
        public Map<String, String> keys() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("burning", "true while lit");
            m.put("progress", "Smelting progress in percent");
            m.put("burn_seconds", "Seconds of fuel left in the current item");
            m.put("input", "Item being smelted");
            m.put("fuel", "Fuel item");
            m.put("output", "Result item");
            m.put("input_count", "Input amount");
            m.put("fuel_count", "Fuel amount");
            m.put("output_count", "Result amount");
            return m;
        }
    }

    static final class SignSource implements DataSource {
        @Override
        public boolean available(LinkContext ctx) { return ctx.blockEntity() instanceof SignBlockEntity; }

        @Override
        public String get(String key, String arg, LinkContext ctx) {
            if (!(ctx.blockEntity() instanceof SignBlockEntity sign)) return null;
            if (key.equals("text")) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 4; i++) {
                    String l = sign.getMessage(i, false).getString();
                    if (!l.isBlank()) { if (sb.length() > 0) sb.append(' '); sb.append(l); }
                }
                return sb.toString();
            }
            if (key.equals("line")) {
                int n;
                try { n = Integer.parseInt(arg.trim()); } catch (NumberFormatException e) { n = 1; }
                if (n < 1 || n > 4) return "";
                return sign.getMessage(n - 1, false).getString();
            }
            return null;
        }

        @Override
        public Map<String, String> keys() {
            return Map.of("line", "One line (add ,1 to ,4: {link.sign.line:board,2})", "text", "All lines joined");
        }
    }

    /** For /aholo info and the editor: "name @ x y z (dimension)". */
    public static String describe(HologramDef.BlockLink l) {
        BlockPos p = l.pos();
        return l.name() + " @ " + p.getX() + " " + p.getY() + " " + p.getZ()
                + (l.dim() == null ? "" : " (" + l.dim().location() + ")");
    }

}
