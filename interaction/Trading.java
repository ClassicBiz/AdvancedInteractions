package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.api.Actions;
import com.luminacity.advancedinteractions.api.Economy;
import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.api.shop.ShopHandler;
import com.luminacity.advancedinteractions.api.shop.Shops;
import com.luminacity.advancedinteractions.api.shop.TradeRequest;
import com.luminacity.advancedinteractions.api.shop.TradeResult;
import com.luminacity.advancedinteractions.config.AIConfig;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * advholo:trade?side=buy|sell&qty=N[&shop=ID][&link=NAME][&item=..&price=..] - a buy or sell handled by
 * a registered shop (or the built-in "basic" admin shop). Quantity is checked here, the shop does the
 * rest; a refused trade fails the action (refunding click costs and running "if it failed" steps).
 */
public final class Trading {
    private Trading() {}

    public static final int MAX_QTY = 10_000;
    private static final Map<UUID, Long> LAST_TRADE = new ConcurrentHashMap<>();

    public static void register() {
        Shops.register(Shops.BASIC, new BasicShop());
        Shops.register(BarterShop.ID, new BarterShop());
        Shops.document(BarterShop.ID, "Item-for-item trade, no economy mod needed: item=ID&cost=N&currency=ID (default emeralds);"
                + " link=NAME trades from that chest/barrel (stock out, payment in)");
        Placeholders.register("trade", new Provider());
    }

    /** Run a trade action for a player on a hologram. */
    public static void run(ServerPlayer player, HologramDef def, Map<String, String> params) {
        if (player == null || def == null) return;
        Map<String, String> p = params == null ? Map.of() : params;

        long now = player.getServer().getTickCount();
        int cd = Math.max(0, AIConfig.get(AIConfig.TRADE_COOLDOWN_TICKS, 4));
        Long last = LAST_TRADE.get(player.getUUID());
        if (cd > 0 && last != null && now - last < cd) { refuse(player, "Slow down a little."); return; }
        LAST_TRADE.put(player.getUUID(), now);

        TradeRequest.Side side = "sell".equalsIgnoreCase(p.getOrDefault("side", "buy")) ? TradeRequest.Side.SELL : TradeRequest.Side.BUY;
        int qty;
        try { qty = (int) Math.floor(Double.parseDouble(p.getOrDefault("qty", "1").replace(",", "").trim())); }
        catch (NumberFormatException e) { refuse(player, "Enter a quantity first."); return; }
        if (qty < 1) { refuse(player, "The quantity must be at least 1."); return; }
        if (qty > MAX_QTY) { refuse(player, "That's too many at once (max " + MAX_QTY + ")."); return; }

        ShopHandler shop = Shops.get(p.get("shop"));
        if (shop == null) { refuse(player, "That shop isn't available on this server."); return; }

        TradeRequest req = request(player, def, side, qty, p);
        TradeResult r;
        try {
            r = shop.trade(req);
        } catch (Exception e) {
            r = TradeResult.fail("The shop had a problem; nothing was traded.");
        }
        if (r == null) r = TradeResult.fail("Nothing was traded.");
        ActionLog.log(player, def, ActionRunner.currentElement(), "trade " + side.name().toLowerCase(Locale.ROOT) + " x" + qty
                + " shop=" + p.getOrDefault("shop", Shops.BASIC) + (p.containsKey("item") ? " item=" + p.get("item") : ""),
                r.success() ? "ok" : "refused: " + r.message());
        if (!r.message().isBlank()) {
            player.displayClientMessage(com.luminacity.advancedinteractions.util.TextFmt.component(r.message())
                    .withStyle(r.success() ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        }
        if (!r.success()) Actions.reportFailure();
        C2S_sync(player, def);
    }

    private static void C2S_sync(ServerPlayer player, HologramDef def) {
        com.luminacity.advancedinteractions.network.c2s.C2S_RequestSync.syncOne(player, def);
    }

    private static void refuse(ServerPlayer player, String msg) {
        player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.RED), true);
        Actions.reportFailure();
    }

    static TradeRequest request(ServerPlayer player, HologramDef def, TradeRequest.Side side, int qty, Map<String, String> p) {
        HologramDef.BlockLink link = BlockLinks.find(def, p.getOrDefault("link", ""));
        return new TradeRequest(player, def.id, ActionRunner.currentElement(), side, qty, Map.copyOf(p),
                link == null ? null : link.dim(), link == null ? null : link.pos());
    }

    public static void clearPlayer(UUID player) { LAST_TRADE.remove(player); }

    /** Give items owed from trades that settled while the player was offline. */
    public static void onLogin(ServerPlayer p) { BasicShop.deliverOwed(p); }

    // ---------------------------------------------------------------- {trade.max:SIDE,ITEM,PRICE[,SHOP[,LINK[,CURRENCY]]]}, {trade.economy}

    static final class Provider implements Placeholders.PlaceholderProvider {
        @Override
        public String resolve(String key, String args, Placeholders.PHContext ctx) {
            // "yes" when an economy mod is installed (for holograms that offer money trades, else item trades).
            if ("economy".equalsIgnoreCase(key)) return Economy.get() != null || Economy.async() != null ? "yes" : "";
            if (!"max".equalsIgnoreCase(key) || ctx == null || !(ctx.viewer() instanceof ServerPlayer sp) || ctx.holo() == null) return "";
            String[] a = (args == null ? "" : args).split(",");
            Map<String, String> p = new HashMap<>();
            p.put("side", a.length > 0 ? a[0].trim() : "buy");
            if (a.length > 1) p.put("item", a[1].trim());
            if (a.length > 2) p.put("price", a[2].trim());
            if (a.length > 3) p.put("shop", a[3].trim());
            if (a.length > 4) p.put("link", a[4].trim());
            if (a.length > 5) p.put("currency", a[5].trim());
            if (p.get("price") != null) p.put("cost", p.get("price")); // barter's per-item cost
            ShopHandler shop = Shops.get(p.get("shop"));
            if (shop == null) return "0";
            TradeRequest.Side side = "sell".equalsIgnoreCase(p.get("side")) ? TradeRequest.Side.SELL : TradeRequest.Side.BUY;
            try {
                int max = shop.maxQuantity(request(sp, ctx.holo(), side, 1, p));
                return max < 0 ? "" : Integer.toString(max);
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public Map<String, String> keys() {
            return Map.of("max:SIDE,ITEM,PRICE[,SHOP[,LINK[,CURRENCY]]]", "Most the viewer can buy/sell now, e.g. {trade.max:buy,minecraft:diamond,10}, "
                    + "{trade.max:buy,,,luminshops,shop} or {trade.max:buy,minecraft:bread,2,barter,stock,minecraft:emerald}",
                    "economy", "\"yes\" when an economy mod is installed (use has(trade.economy) to offer money trades, else item trades)");
        }
    }

    // ---------------------------------------------------------------- built-in admin shop

    /** Sells/buys {@code item} for {@code price} each through the registered economy. Unlimited stock. */
    static final class BasicShop implements ShopHandler {
        private static Item item(TradeRequest r) {
            ResourceLocation rl = ResourceLocation.tryParse(r.param("item", "").toLowerCase(Locale.ROOT));
            Item it = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
            return it == null || it == Items.AIR ? null : it;
        }

        private static double price(TradeRequest r) {
            try { return Math.max(0, Double.parseDouble(r.param("price", "0").replace(",", ""))); } catch (NumberFormatException e) { return -1; }
        }

        @Override
        public int maxQuantity(TradeRequest r) {
            Item it = item(r);
            double price = price(r);
            if (it == null || price < 0) return 0;
            ServerPlayer p = r.player();
            if (r.side() == TradeRequest.Side.SELL) return Math.min(MAX_QTY, count(p.getInventory(), it));
            int space = space(p.getInventory(), it);
            if (price <= 0) return Math.min(MAX_QTY, space);
            Economy.Provider eco = Economy.get();
            double money;
            if (eco != null) money = eco.balance(p);
            else if (Economy.async() != null) {
                money = Economy.async().balanceEstimate(p);
                if (money < 0) return Math.min(MAX_QTY, space); // unknown: let the bank decide
            } else return 0;
            return (int) Math.max(0, Math.min(Math.min(MAX_QTY, space), Math.floor(money / price + 1e-9)));
        }

        @Override
        public TradeResult trade(TradeRequest r) {
            Item it = item(r);
            if (it == null) return TradeResult.fail("This shop has no valid item set.");
            double price = price(r);
            if (price < 0) return TradeResult.fail("This shop has no valid price set.");
            ServerPlayer p = r.player();
            int qty = r.quantity();
            double total = price * qty;
            Economy.Provider eco = Economy.get();
            if (total > 0 && eco == null && Economy.async() != null) return tradeAsync(r, it, qty, total);
            if (total > 0 && eco == null) return TradeResult.fail("No economy mod is installed.");
            String name = new ItemStack(it).getHoverName().getString();
            String money = eco == null ? String.format(Locale.ROOT, "%.2f", total) : eco.format(total);

            if (r.side() == TradeRequest.Side.BUY) {
                if (space(p.getInventory(), it) < qty) return TradeResult.fail("Not enough room in your inventory.");
                if (total > 0 && !eco.withdraw(p, total)) return TradeResult.fail("You can't afford that (" + money + ").");
                give(p, it, qty);
                return TradeResult.ok("Bought " + qty + " " + name + " for " + money + ".");
            }
            if (count(p.getInventory(), it) < qty) return TradeResult.fail("You don't have " + qty + " " + name + ".");
            take(p.getInventory(), it, qty);
            if (total > 0 && !eco.deposit(p, total)) {
                give(p, it, qty); // put them back
                return TradeResult.fail("The payment failed; your items were returned.");
            }
            return TradeResult.ok("Sold " + qty + " " + name + " for " + money + ".");
        }

        /** Players with a payment still waiting for the bank's answer (one at a time). */
        private static final java.util.Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

        /**
         * Async economy (e.g. EconomyCore): goods are held until the bank answers. Buying: the items are
         * given only after the charge succeeds. Selling: the items are taken first and returned if the
         * payment fails.
         */
        private static TradeResult tradeAsync(TradeRequest r, Item it, int qty, double total) {
            Economy.AsyncProvider eco = Economy.async();
            ServerPlayer p = r.player();
            UUID id = p.getUUID();
            var server = p.getServer();
            String name = new ItemStack(it).getHoverName().getString();
            String money = eco.format(total);
            if (!IN_FLIGHT.add(id)) return TradeResult.fail("Still processing your last payment - one moment.");
            String details = "hologram_shop item=" + ForgeRegistries.ITEMS.getKey(it) + " qty=" + qty + " holo=" + r.hologramId();
            try {
                if (r.side() == TradeRequest.Side.BUY) {
                    if (space(p.getInventory(), it) < qty) { IN_FLIGHT.remove(id); return TradeResult.fail("Not enough room in your inventory."); }
                    double est = eco.balanceEstimate(p);
                    if (est >= 0 && est + 1e-9 < total) { IN_FLIGHT.remove(id); return TradeResult.fail("You can't afford that (" + money + ")."); }
                    eco.charge(p, total, name, details, ok -> server.execute(() -> {
                        IN_FLIGHT.remove(id);
                        ServerPlayer now = server.getPlayerList().getPlayer(id);
                        if (!Boolean.TRUE.equals(ok)) {
                            if (now != null) now.displayClientMessage(Component.literal("Payment failed; nothing was bought.").withStyle(ChatFormatting.RED), true);
                            return;
                        }
                        if (now == null) { // paid, then logged out: hand the items over when they're back
                            owe(id, it, qty);
                            return;
                        }
                        give(now, it, qty);
                        now.displayClientMessage(Component.literal("Bought " + qty + " " + name + " for " + money + ".").withStyle(ChatFormatting.GREEN), true);
                    }));
                    return TradeResult.ok("Paying " + money + "...");
                }
                if (count(p.getInventory(), it) < qty) { IN_FLIGHT.remove(id); return TradeResult.fail("You don't have " + qty + " " + name + "."); }
                take(p.getInventory(), it, qty); // held until the payment answer
                eco.pay(p, total, "Sold " + qty + " " + name, details, ok -> server.execute(() -> {
                    IN_FLIGHT.remove(id);
                    ServerPlayer now = server.getPlayerList().getPlayer(id);
                    if (Boolean.TRUE.equals(ok)) {
                        if (now != null) now.displayClientMessage(Component.literal("Sold " + qty + " " + name + " for " + money + ".").withStyle(ChatFormatting.GREEN), true);
                        return;
                    }
                    if (now != null) {
                        give(now, it, qty);
                        now.displayClientMessage(Component.literal("The payment failed; your items were returned.").withStyle(ChatFormatting.RED), true);
                    } else {
                        owe(id, it, qty); // returned at their next login
                    }
                }));
                return TradeResult.ok("Selling for " + money + "...");
            } catch (RuntimeException e) {
                IN_FLIGHT.remove(id);
                throw e;
            }
        }

        /** Items owed to players who logged out while a payment was settling (given at next login). */
        private static final Map<UUID, java.util.List<ItemStack>> OWED = new ConcurrentHashMap<>();

        private static void owe(UUID player, Item it, int qty) {
            OWED.computeIfAbsent(player, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>())).add(new ItemStack(it, qty));
            com.mojang.logging.LogUtils.getLogger().info("[AdvancedInteractions] Holding {}x {} for {} until they log back in", qty, it, player);
        }

        static void deliverOwed(ServerPlayer p) {
            var list = OWED.remove(p.getUUID());
            if (list == null) return;
            for (ItemStack s : list) give(p, s.getItem(), s.getCount());
            p.displayClientMessage(Component.literal("You received items from a shop trade that finished while you were away.").withStyle(ChatFormatting.GREEN), false);
        }

        /** Plain (no NBT) stacks of the item. */
        private static boolean plain(ItemStack s, Item it) { return s.is(it) && !s.hasTag(); }

        static int count(Inventory inv, Item it) {
            int n = 0;
            for (int i = 0; i < inv.items.size(); i++) if (plain(inv.items.get(i), it)) n += inv.items.get(i).getCount();
            return n;
        }

        static int space(Inventory inv, Item it) {
            int max = new ItemStack(it).getMaxStackSize(), n = 0;
            for (int i = 0; i < inv.items.size(); i++) {
                ItemStack s = inv.items.get(i);
                if (s.isEmpty()) n += max;
                else if (plain(s, it)) n += Math.max(0, max - s.getCount());
            }
            return n;
        }

        static void take(Inventory inv, Item it, int qty) {
            for (int i = 0; i < inv.items.size() && qty > 0; i++) {
                ItemStack s = inv.items.get(i);
                if (!plain(s, it)) continue;
                int t = Math.min(qty, s.getCount());
                s.shrink(t);
                qty -= t;
            }
            inv.setChanged();
        }

        static void give(ServerPlayer p, Item it, int qty) {
            int max = new ItemStack(it).getMaxStackSize();
            while (qty > 0) {
                int n = Math.min(max, qty);
                ItemStack s = new ItemStack(it, n);
                if (!p.getInventory().add(s) && !s.isEmpty()) p.drop(s, false);
                qty -= n;
            }
        }
    }
}
