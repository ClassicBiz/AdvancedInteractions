package com.luminacity.advancedinteractions.api.shop;

/** What a shop answered: whether the trade happened and what to tell the player. */
public record TradeResult(boolean success, String message) {
    public static TradeResult ok(String message) { return new TradeResult(true, message == null ? "" : message); }
    public static TradeResult fail(String message) { return new TradeResult(false, message == null ? "" : message); }
}
