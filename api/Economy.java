package com.luminacity.advancedinteractions.api;

import com.luminacity.advancedinteractions.AdvancedInteractions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Hook for an economy mod, used by button money costs. One provider at a time (last one wins).
 *
 * <pre>
 * // Direct:
 * Economy.register(new Economy.Provider() { ... });
 * // No compile-time dependency (send during InterModEnqueueEvent):
 * InterModComms.sendTo("advancedinteractions", "register_economy", () -&gt; Map.of(
 *     "balance",  (Function&lt;ServerPlayer, Double&gt;) p -&gt; ...,
 *     "withdraw", (BiFunction&lt;ServerPlayer, Double, Boolean&gt;) (p, amount) -&gt; ...,
 *     "deposit",  (BiFunction&lt;ServerPlayer, Double, Boolean&gt;) (p, amount) -&gt; ...,   // used for refunds
 *     "format",   (Function&lt;Double, String&gt;) amount -&gt; ...));                      // optional
 * </pre>
 */
public final class Economy {
    private Economy() {}

    public static final String METHOD_REGISTER = "register_economy";
    private static final Logger LOG = LoggerFactory.getLogger("AdvancedInteractions");

    public interface Provider {
        double balance(ServerPlayer player);
        /** Take money; false (and no change) when the player can't afford it. */
        boolean withdraw(ServerPlayer player, double amount);
        boolean deposit(ServerPlayer player, double amount);
        default String format(double amount) { return String.format(Locale.ROOT, "$%,.2f", amount); }
    }

    /**
     * For economies whose payments finish later (a bank server answering over the network, like
     * EconomyCore). The built-in shop holds the goods until the payment answer arrives.
     * Callbacks may arrive on any thread; AdvancedInteractions moves them to the server thread.
     */
    public interface AsyncProvider {
        /** Best-known money the player can spend right now (may be cached), or -1 when unknown. */
        double balanceEstimate(ServerPlayer player);
        /** Charge the player for a purchase from the server (e.g. paid to the city). */
        void charge(ServerPlayer player, double amount, String item, String details, java.util.function.Consumer<Boolean> done);
        /** Pay the player (e.g. from the city) for something they sold. */
        void pay(ServerPlayer player, double amount, String reason, String details, java.util.function.Consumer<Boolean> done);
        default String format(double amount) { return String.format(Locale.ROOT, "$%,.2f", amount); }
    }

    private static volatile AsyncProvider asyncProvider;

    /** Register an async economy (used when no synchronous {@link Provider} is registered). */
    public static void registerAsync(AsyncProvider p) {
        asyncProvider = p;
        if (p != null) LOG.info("Async economy registered");
    }

    /** @return the async economy, or null */
    public static AsyncProvider async() { return asyncProvider; }

    private static volatile Provider provider;

    public static void register(Provider p) { provider = p; }

    /** @return the registered provider, or null when no economy mod is installed */
    public static Provider get() { return provider; }

    @SuppressWarnings("unchecked")
    public static void process(InterModProcessEvent evt) {
        InterModComms.getMessages(AdvancedInteractions.MODID, METHOD_REGISTER::equals).forEach(msg -> {
            try {
                if (!(msg.messageSupplier().get() instanceof Map<?, ?> m)
                        || !(m.get("balance") instanceof Function<?, ?> bal)
                        || !(m.get("withdraw") instanceof BiFunction<?, ?, ?> wd)
                        || !(m.get("deposit") instanceof BiFunction<?, ?, ?> dep)) {
                    LOG.warn("{} sent {} without balance/withdraw/deposit functions; ignored.", msg.senderModId(), METHOD_REGISTER);
                    return;
                }
                var balance = (Function<ServerPlayer, Double>) bal;
                var withdraw = (BiFunction<ServerPlayer, Double, Boolean>) wd;
                var deposit = (BiFunction<ServerPlayer, Double, Boolean>) dep;
                var format = m.get("format") instanceof Function<?, ?> f ? (Function<Double, String>) f : null;
                register(new Provider() {
                    @Override public double balance(ServerPlayer p) { Double d = balance.apply(p); return d == null ? 0 : d; }
                    @Override public boolean withdraw(ServerPlayer p, double a) { return Boolean.TRUE.equals(withdraw.apply(p, a)); }
                    @Override public boolean deposit(ServerPlayer p, double a) { return Boolean.TRUE.equals(deposit.apply(p, a)); }
                    @Override public String format(double a) { return format == null ? Provider.super.format(a) : format.apply(a); }
                });
                LOG.info("Economy provided by {}", msg.senderModId());
            } catch (Throwable t) {
                LOG.warn("Bad {} message from {}: {}", METHOD_REGISTER, msg.senderModId(), t.toString());
            }
        });
    }
}
