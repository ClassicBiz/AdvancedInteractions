package com.luminacity.advancedinteractions.api.shop;

/**
 * Carries out buys and sells for holograms. A store mod registers one under its id
 * ({@code Shops.register("lumina_store", handler)}) and holograms use it with
 * {@code advholo:trade?shop=lumina_store&side=buy&qty={input.qty}&...}.
 * Called on the server thread; do all money and item moves here (e.g. through EconomyCore).
 */
public interface ShopHandler {
    TradeResult trade(TradeRequest request);

    /**
     * The most this player could trade right now (stock, money, inventory space, limits), or -1 if
     * unknown. Shown with {@code {trade.max:...}} and used to grey out buttons ("Disabled if").
     * The quantity in the request is ignored here.
     */
    default int maxQuantity(TradeRequest request) { return -1; }
}
