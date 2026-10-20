package com.luminacity.advancedinteractions.interaction;

import com.luminacity.advancedinteractions.api.shop.ShopHandler;
import com.luminacity.advancedinteractions.api.shop.TradeRequest;
import com.luminacity.advancedinteractions.api.shop.TradeResult;
import com.luminacity.advancedinteractions.data.HologramSavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Built-in "barter" shop: item-for-item trades, no economy mod involved.
 * <pre>
 * advholo:trade?shop=barter&amp;side=buy|sell&amp;qty=N&amp;item=minecraft:bread&amp;cost=2[&amp;currency=minecraft:emerald][&amp;link=NAME]
 * </pre>
 * {@code cost} is how many currency items one item costs (a whole number); {@code currency} defaults to emeralds.
 *
 * <p>With {@code link=NAME} pointing at a chest, barrel or other container (a double chest counts as one):</p>
 * <ul>
 *   <li>buy: the items come out of the container and the payment goes in - first into the slots the items
 *       came from, then onto matching stacks, then into empty slots;</li>
 *   <li>sell: the items go into the container and the payment comes out of it.</li>
 * </ul>
 * <p>Without a container the server is the other side, with unlimited stock (like a villager). That's only
 * allowed on staff holograms: a player-owned hologram must trade through its linked container.</p>
 *
 * <p>Everything is checked before anything moves, so a trade happens completely or not at all.</p>
 */
public final class BarterShop implements ShopHandler {
    public static final String ID = "barter";
    public static final String DEFAULT_CURRENCY = "minecraft:emerald";

    private record Setup(Item item, Item currency, int cost) {}

    /** The container to trade with, or null for server stock. {@code error} is set when the trade can't go ahead. */
    private record Stock(Container container, String error) {}

    @Override
    public int maxQuantity(TradeRequest r) {
        Setup s = setup(r);
        if (s == null) return 0;
        Stock stock = stock(r);
        if (stock.error() != null) return 0;
        ServerPlayer p = r.player();
        Container c = stock.container();
        if (r.side() == TradeRequest.Side.BUY) {
            int max = Math.min(Trading.MAX_QTY, Trading.BasicShop.space(p.getInventory(), s.item()));
            if (s.cost() > 0) max = Math.min(max, Trading.BasicShop.count(p.getInventory(), s.currency()) / s.cost());
            if (c != null) max = Math.min(max, count(contents(c), s.item()));
            return Math.max(0, max);
        }
        int max = Math.min(Trading.MAX_QTY, Trading.BasicShop.count(p.getInventory(), s.item()));
        if (c != null && s.cost() > 0) max = Math.min(max, count(contents(c), s.currency()) / s.cost());
        return Math.max(0, max);
    }

    @Override
    public TradeResult trade(TradeRequest r) {
        Setup s = setup(r);
        if (s == null) return TradeResult.fail("This trade isn't set up right (it needs item=, a whole-number cost= and a valid currency=).");
        Stock stock = stock(r);
        if (stock.error() != null) return TradeResult.fail(stock.error());

        ServerPlayer p = r.player();
        int qty = r.quantity();
        int total = s.cost() * qty;
        String itemName = name(s.item(), qty);
        String price = total + " " + name(s.currency(), total);
        Container c = stock.container();

        if (r.side() == TradeRequest.Side.BUY) {
            if (Trading.BasicShop.count(p.getInventory(), s.currency()) < total) return TradeResult.fail("You need " + price + ".");
            if (Trading.BasicShop.space(p.getInventory(), s.item()) < qty) return TradeResult.fail("Not enough room in your inventory.");
            if (c != null) {
                List<ItemStack> next = contents(c);
                if (count(next, s.item()) < qty) return TradeResult.fail("Out of stock.");
                List<Integer> freed = remove(next, s.item(), qty);
                if (insert(next, s.currency(), total, freed) > 0) return TradeResult.fail("The shop's storage is full.");
                apply(c, next);
            }
            Trading.BasicShop.take(p.getInventory(), s.currency(), total);
            Trading.BasicShop.give(p, s.item(), qty);
            return TradeResult.ok("Bought " + qty + " " + itemName + " for " + price + ".");
        }

        if (Trading.BasicShop.count(p.getInventory(), s.item()) < qty) return TradeResult.fail("You don't have " + qty + " " + itemName + ".");
        if (c != null) {
            List<ItemStack> next = contents(c);
            if (count(next, s.currency()) < total) return TradeResult.fail("The shop can't pay for that right now.");
            List<Integer> freed = remove(next, s.currency(), total);
            if (insert(next, s.item(), qty, freed) > 0) return TradeResult.fail("The shop's storage is full.");
            apply(c, next);
        }
        Trading.BasicShop.take(p.getInventory(), s.item(), qty);
        Trading.BasicShop.give(p, s.currency(), total);
        return TradeResult.ok("Sold " + qty + " " + itemName + " for " + price + ".");
    }

    // ---------------------------------------------------------------- setup

    private static Setup setup(TradeRequest r) {
        Item item = item(r.param("item", ""));
        Item currency = item(r.param("currency", DEFAULT_CURRENCY));
        int cost;
        try { cost = Integer.parseInt(r.param("cost", r.param("price", "1")).trim()); } catch (NumberFormatException e) { return null; }
        if (item == null || currency == null || cost < 0 || item == currency) return null;
        return new Setup(item, currency, cost);
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id.trim().toLowerCase(Locale.ROOT));
        Item it = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        return it == null || it == Items.AIR ? null : it;
    }

    private static Stock stock(TradeRequest r) {
        boolean named = !r.param("link", "").isBlank();
        Container c = null;
        if (r.linkPos() != null && r.linkDim() != null) {
            ServerLevel level = r.player().getServer().getLevel(r.linkDim());
            if (level == null || !level.isLoaded(r.linkPos())) {
                if (named) return new Stock(null, "The shop's storage isn't loaded right now.");
            } else {
                c = container(level, r);
                if (c == null && named) return new Stock(null, "The shop's linked block isn't a container.");
            }
        }
        if (c != null) return new Stock(c, null);
        // Server stock only for staff holograms; players' holograms must trade from their own container.
        var loc = HologramSavedData.findAnywhere(r.player().getServer(), r.hologramId());
        if (loc == null || loc.def().owner != null) return new Stock(null, "This shop needs a linked chest or barrel to trade from.");
        return new Stock(null, null);
    }

    private static Container container(ServerLevel level, TradeRequest r) {
        BlockState state = level.getBlockState(r.linkPos());
        if (state.getBlock() instanceof ChestBlock chest) {
            Container both = ChestBlock.getContainer(chest, state, level, r.linkPos(), true); // a double chest as one
            if (both != null) return both;
        }
        BlockEntity be = level.getBlockEntity(r.linkPos());
        return be instanceof Container c ? c : null;
    }

    // ---------------------------------------------------------------- container simulation

    private static boolean plain(ItemStack s, Item it) { return s.is(it) && !s.hasTag(); }

    private static List<ItemStack> contents(Container c) {
        List<ItemStack> out = new ArrayList<>(c.getContainerSize());
        for (int i = 0; i < c.getContainerSize(); i++) out.add(c.getItem(i).copy());
        return out;
    }

    private static int count(List<ItemStack> slots, Item it) {
        int n = 0;
        for (ItemStack s : slots) if (plain(s, it)) n += s.getCount();
        return n;
    }

    /** Takes qty plain items; returns the slots that ended up empty. */
    private static List<Integer> remove(List<ItemStack> slots, Item it, int qty) {
        List<Integer> freed = new ArrayList<>();
        for (int i = 0; i < slots.size() && qty > 0; i++) {
            ItemStack s = slots.get(i);
            if (!plain(s, it)) continue;
            int t = Math.min(qty, s.getCount());
            s.shrink(t);
            qty -= t;
            if (s.isEmpty()) { slots.set(i, ItemStack.EMPTY); freed.add(i); }
        }
        return freed;
    }

    /** Puts qty items in: freed slots first, then matching stacks, then empty slots. Returns what didn't fit. */
    private static int insert(List<ItemStack> slots, Item it, int qty, List<Integer> freed) {
        int max = new ItemStack(it).getMaxStackSize();
        for (int i : freed) {
            if (qty <= 0) break;
            if (!slots.get(i).isEmpty()) continue;
            int n = Math.min(max, qty);
            slots.set(i, new ItemStack(it, n));
            qty -= n;
        }
        for (int i = 0; i < slots.size() && qty > 0; i++) {
            ItemStack s = slots.get(i);
            if (!plain(s, it) || s.getCount() >= max) continue;
            int n = Math.min(max - s.getCount(), qty);
            s.grow(n);
            qty -= n;
        }
        for (int i = 0; i < slots.size() && qty > 0; i++) {
            if (!slots.get(i).isEmpty()) continue;
            int n = Math.min(max, qty);
            slots.set(i, new ItemStack(it, n));
            qty -= n;
        }
        return qty;
    }

    private static void apply(Container c, List<ItemStack> slots) {
        for (int i = 0; i < slots.size(); i++) {
            if (!ItemStack.matches(c.getItem(i), slots.get(i))) c.setItem(i, slots.get(i));
        }
        c.setChanged();
    }

    private static String name(Item it, int n) {
        String s = new ItemStack(it).getHoverName().getString();
        return n == 1 || s.endsWith("s") ? s : s + "s";
    }
}
