package com.luminacity.advancedinteractions.api;

import com.luminacity.advancedinteractions.hologram.HologramDef;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholder engine for holograms, and the public API other mods use to add values.
 *
 * <p>Syntax:</p>
 * <ul>
 *   <li>{@code {namespace.key}}, {@code {namespace.key:args}}, {@code {namespace.key[args]}}</li>
 *   <li>{@code {namespace.key|filter|filter:arg}} - output filters, see {@link PlaceholderFilters}</li>
 *   <li>{@code {key}} - template variable, same as {@code {data.key}}</li>
 * </ul>
 *
 * <p>Adding placeholders from another mod (pick one):</p>
 * <pre>
 * // Whole namespace: {mymod.anything}
 * Placeholders.register("mymod", (key, args, ctx) -&gt; ...);
 * // Single keys, with a description for /aholo placeholders and tab completion.
 * // Works for new namespaces and for extending built-in ones ({player.balance}).
 * Placeholders.registerKey("player", "balance", "Bank balance", (args, ctx) -&gt; ...);
 * // No compile-time dependency: see {@link PlaceholderIMC}.
 * </pre>
 *
 * <p>Values resolve on the server for the viewing player and are synced to that player's client
 * (about every 0.5s while the hologram is visible), unless the provider declares the key
 * client-side via {@link PlaceholderProvider#isClientSide(String)}.</p>
 */
public final class Placeholders {

    /** Reserved prefix inside {@link com.luminacity.advancedinteractions.hologram.HologramDef#meta} for binding links. */
    public static final String META_BIND_PREFIX = "@bind.";

    public record PHContext(Level level, Player viewer, HologramDef holo) {}

    public interface PlaceholderProvider {
        String resolve(String key, String args, PHContext ctx);

        /**
         * Optional hint for how frequently a provider should be polled.
         * The engine itself does not enforce polling; this is informational.
         */
        default int minPeriodTicks() { return 20; }

        /**
         * True when the client can resolve this key itself (from data it already has, e.g. its own
         * position). Client-side keys update every frame and are never sent over the network.
         * Default false: resolved on the server and synced.
         */
        default boolean isClientSide(String key) { return false; }

        /** Documented keys ("key" or "key:<arg>") -> description, for /aholo placeholders and tab completion. */
        default Map<String, String> keys() { return Map.of(); }
    }

    /** Resolver for a single registered key. */
    public interface KeyResolver {
        String resolve(String args, PHContext ctx);
    }

    /**
     * Optional client-side hook for resolving server-only placeholder values.
     *
     * The resolver is consulted FIRST on the logical client. If it returns null,
     * the normal namespace provider is used.
     */
    public interface RemoteResolver {
        /** @return resolved value, or null if not available */
        String resolve(String namespace, String key, String args, PHContext ctx);
    }

    private static final Map<String, PlaceholderProvider> REGISTRY = new ConcurrentHashMap<>();
    private static volatile RemoteResolver REMOTE = null;

    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_\\-]+");

    private Placeholders() {}

    public static void setRemoteResolver(RemoteResolver resolver) {
        REMOTE = resolver;
    }

    // ---------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------

    /**
     * Register a provider for a whole namespace. If keys were registered for this namespace with
     * {@link #registerKey}, they keep priority and this provider answers everything else.
     */
    public static void register(String namespace, PlaceholderProvider provider) {
        if (namespace == null || namespace.isBlank() || provider == null) return;
        String ns = normalizeNamespace(namespace);
        PlaceholderProvider p = adaptProxy(provider);
        REGISTRY.compute(ns, (k, existing) -> {
            if (existing instanceof KeyedProvider kp) { kp.fallback = p; return kp; }
            return p;
        });
    }

    /**
     * Register a provider only if nothing is registered for the namespace yet. A later {@link #register}
     * replaces it - used for built-in defaults a mod can take over (e.g. {econ.*} until an economy mod
     * registers its own).
     */
    public static void registerFallback(String namespace, PlaceholderProvider provider) {
        if (namespace == null || namespace.isBlank() || provider == null) return;
        REGISTRY.putIfAbsent(normalizeNamespace(namespace), adaptProxy(provider));
    }

    /** Register one server-resolved key, e.g. registerKey("player", "balance", "Bank balance", ...). */
    public static void registerKey(String namespace, String key, String description, KeyResolver resolver) {
        registerKey(namespace, key, description, false, resolver);
    }

    /** @param clientSide true only if the resolver works on the client with client-side data. */
    public static void registerKey(String namespace, String key, String description, boolean clientSide, KeyResolver resolver) {
        if (namespace == null || namespace.isBlank() || key == null || key.isBlank() || resolver == null) return;
        String ns = normalizeNamespace(namespace);
        REGISTRY.compute(ns, (k, existing) -> {
            KeyedProvider kp = existing instanceof KeyedProvider e ? e : new KeyedProvider(existing);
            kp.keys.put(key.toLowerCase(Locale.ROOT).trim(), new KeyEntry(resolver, description == null ? "" : description, clientSide));
            return kp;
        });
    }

    public static void unregister(String namespace) {
        if (namespace != null) REGISTRY.remove(normalizeNamespace(namespace));
    }

    /** "econ_backend" was the old two-step economy hook; it is now just the "econ" namespace. */
    private static String normalizeNamespace(String namespace) {
        String ns = namespace.toLowerCase(Locale.ROOT).trim();
        return ns.equals("econ_backend") ? "econ" : ns;
    }

    /**
     * Mods that register through a reflection Proxy only implement resolve(); calls to the newer
     * default methods would reach their handler and return the wrong type. Pin those to defaults.
     */
    private static PlaceholderProvider adaptProxy(PlaceholderProvider p) {
        if (!Proxy.isProxyClass(p.getClass())) return p;
        return new PlaceholderProvider() {
            @Override public String resolve(String key, String args, PHContext ctx) { return p.resolve(key, args, ctx); }
            @Override public int minPeriodTicks() {
                try { return p.minPeriodTicks(); } catch (RuntimeException e) { return 20; }
            }
            /** A proxy may document its keys too (return a Map from "keys"); anything else counts as none. */
            @Override @SuppressWarnings("unchecked") public Map<String, String> keys() {
                try {
                    Object k = ((Object) p.keys());
                    return k instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
                } catch (RuntimeException e) {
                    return Map.of();
                }
            }
        };
    }

    private record KeyEntry(KeyResolver resolver, String description, boolean clientSide) {}

    /** Namespace built from {@link #registerKey} entries, optionally backed by a whole-namespace provider. */
    private static final class KeyedProvider implements PlaceholderProvider {
        final Map<String, KeyEntry> keys = new ConcurrentHashMap<>();
        volatile PlaceholderProvider fallback;

        KeyedProvider(PlaceholderProvider fallback) { this.fallback = fallback; }

        @Override public String resolve(String key, String args, PHContext ctx) {
            KeyEntry e = keys.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
            if (e != null) return e.resolver().resolve(args, ctx);
            return fallback == null ? "" : fallback.resolve(key, args, ctx);
        }

        @Override public int minPeriodTicks() { return fallback == null ? 20 : fallback.minPeriodTicks(); }

        @Override public boolean isClientSide(String key) {
            KeyEntry e = keys.get(key == null ? "" : key.toLowerCase(Locale.ROOT));
            if (e != null) return e.clientSide();
            return fallback != null && fallback.isClientSide(key);
        }

        @Override public Map<String, String> keys() {
            Map<String, String> out = new LinkedHashMap<>();
            if (fallback != null) out.putAll(fallback.keys());
            keys.forEach((k, e) -> out.put(k, e.description()));
            return out;
        }
    }

    /**
     * @return a live view of registered placeholder namespaces.
     */
    public static Set<String> namespaces() {
        return REGISTRY.keySet();
    }

    public static Optional<PlaceholderProvider> get(String namespace) {
        if (namespace == null) return Optional.empty();
        return Optional.ofNullable(REGISTRY.get(normalizeNamespace(namespace)));
    }

    /** Extra documentation added with {@link #document}, by namespace. */
    private static final Map<String, Map<String, String>> DOCS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Document keys of a namespace for the editor's placeholder picker and /aholo placeholders, for mods
     * that can't override {@link PlaceholderProvider#keys()} (reflection or IMC registration).
     * Keys are written as used after the namespace: "player.balance", "property.price:TARGET".
     */
    public static void document(String namespace, Map<String, String> keys) {
        if (namespace == null || keys == null || keys.isEmpty()) return;
        DOCS.computeIfAbsent(normalizeNamespace(namespace), k -> new java.util.concurrent.ConcurrentHashMap<>()).putAll(keys);
    }

    /** Documented keys of one namespace, sorted ("key" -> description). */
    public static Map<String, String> describe(String namespace) {
        PlaceholderProvider p = get(namespace).orElse(null);
        if (p == null) return Map.of();
        Map<String, String> out = new TreeMap<>();
        try { out.putAll(p.keys()); } catch (RuntimeException ignored) { }
        Map<String, String> extra = DOCS.get(normalizeNamespace(namespace));
        if (extra != null) out.putAll(extra);
        return out;
    }

    /** True if a canonical token ("ns.key[:args]") can be resolved by the client without the server. */
    public static boolean isClientResolvable(String canonical) {
        Parts p = parseBase(canonical);
        if (p == null) return false;
        PlaceholderProvider provider = REGISTRY.get(normalizeNamespace(p.namespace()));
        if (provider == null) return false;
        try { return provider.isClientSide(p.key().toLowerCase(Locale.ROOT)); } catch (RuntimeException e) { return false; }
    }

    // ---------------------------------------------------------------------
    // Meta binding helpers
    // ---------------------------------------------------------------------

    /**
     * Read a binding link from the hologram meta map.
     *
     * Example stored key: "@bind.estate" -> "testprop".
     *
     * @param bindKey key without the prefix ("estate", "econ.bank", "mtr.station.id", ...)
     */
    public static String getMetaBinding(PHContext ctx, String bindKey) {
        if (ctx == null || ctx.holo() == null || ctx.holo().meta == null) return "";
        if (bindKey == null || bindKey.isBlank()) return "";
        String k = META_BIND_PREFIX + bindKey.toLowerCase(Locale.ROOT).trim();
        String v = ctx.holo().meta.get(k);
        return v == null ? "" : v;
    }

    /**
     * Resolve a binding link for a namespace/key pair.
     *
     * Lookup order:
     *  1) @bind.<namespace>.<key>
     *  2) @bind.<namespace>
     */
    public static String getBindingFor(PHContext ctx, String namespace, String key) {
        if (namespace == null || namespace.isBlank()) return "";
        String ns = namespace.toLowerCase(Locale.ROOT).trim();
        String k = key == null ? "" : key.toLowerCase(Locale.ROOT).trim();
        if (!k.isEmpty()) {
            String v = getMetaBinding(ctx, ns + "." + k);
            if (v != null && !v.isEmpty()) return v;
        }
        return getMetaBinding(ctx, ns);
    }

    /** Set a binding link into a hologram's meta map (server-side helper). */
    public static void setMetaBinding(PHContext ctx, String bindKey, String value) {
        if (ctx == null || ctx.holo() == null) return;
        if (ctx.holo().meta == null) return;
        if (bindKey == null || bindKey.isBlank()) return;
        String k = META_BIND_PREFIX + bindKey.toLowerCase(Locale.ROOT).trim();
        if (value == null) value = "";
        ctx.holo().meta.put(k, value);
    }

    /** Remove a binding link from a hologram's meta map (server-side helper). */
    public static void removeMetaBinding(PHContext ctx, String bindKey) {
        if (ctx == null || ctx.holo() == null || ctx.holo().meta == null) return;
        if (bindKey == null || bindKey.isBlank()) return;
        String k = META_BIND_PREFIX + bindKey.toLowerCase(Locale.ROOT).trim();
        ctx.holo().meta.remove(k);
    }

    // ---------------------------------------------------------------------
    // Resolution
    // ---------------------------------------------------------------------

    /** Replace all placeholders in the given string. */
    public static String apply(String input, PHContext ctx) {
        if (input == null || input.isEmpty()) return "";
        if (input.indexOf('{') < 0) return input;
        // Placeholders may nest inside another's filter: {link.luminshop.buy:shop|mul:{input.qty}} resolves
        // {input.qty} first. Resolved values are never scanned again for more placeholders.
        StringBuilder out = new StringBuilder(input.length());
        int i = 0, n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            int end = c == '{' ? matchingBrace(input, i) : -1;
            if (end < 0) { out.append(c); i++; continue; }
            String inner = input.substring(i + 1, end);
            if (inner.indexOf('{') >= 0) inner = apply(inner, ctx);
            String repl = resolveToken(inner, ctx);
            out.append(repl == null ? "" : repl);
            i = end + 1;
        }
        return out.toString();
    }

    /** Resolve a single token body (without surrounding braces), filters included. */
    public static String resolveToken(String raw, PHContext ctx) {
        if (raw == null) return "";
        int bar = raw.indexOf('|');
        Parts p = parseBase(bar < 0 ? raw : raw.substring(0, bar));
        if (p == null) return "";
        String v = resolve(p.namespace, p.key, p.args, ctx);
        if (bar < 0) return v;
        List<String> filters = new ArrayList<>();
        Collections.addAll(filters, raw.substring(bar + 1).split("\\|"));
        return PlaceholderFilters.apply(v, filters);
    }

    /**
     * Canonical cache key for a token body (without surrounding braces). Filters are not part of it:
     * the server syncs the raw value once and each filter variant is applied where it is displayed.
     */
    public static String canonicalize(String raw) {
        if (raw == null) return "";
        int bar = raw.indexOf('|');
        Parts p = parseBase(bar < 0 ? raw : raw.substring(0, bar));
        return p == null ? "" : p.canonical();
    }

    /** Extract canonical placeholder keys used in a text block. */
    public static Set<String> extractCanonicalTokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isEmpty()) return out;
        collectTokens(text, out);
        return out;
    }

    /** Every placeholder in the text, including ones nested in another's filter (and the outer one). */
    private static void collectTokens(String text, Set<String> out) {
        int i = 0, n = text.length();
        while (i < n) {
            int end = text.charAt(i) == '{' ? matchingBrace(text, i) : -1;
            if (end < 0) { i++; continue; }
            String inner = text.substring(i + 1, end);
            if (inner.indexOf('{') >= 0) collectTokens(inner, out);
            // The outer token's own key: everything before its filters / nested parts.
            int cut = inner.indexOf('{');
            String base = cut >= 0 ? inner.substring(0, cut) : inner;
            int bar = base.indexOf('|');
            String c = canonicalize(bar >= 0 ? base.substring(0, bar) : base);
            if (c != null && !c.isEmpty()) out.add(c);
            i = end + 1;
        }
    }

    /** Index of the '}' closing the '{' at {@code open} (nesting allowed), or -1; "{}" doesn't count. */
    private static int matchingBrace(String s, int open) {
        int depth = 0;
        for (int j = open; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return j > open + 1 ? j : -1;
        }
        return -1;
    }

    public record Parts(String namespace, String key, String args) {
        public String canonical() {
            String base = (namespace == null ? "" : namespace) + "." + (key == null ? "" : key);
            if (args != null && !args.isEmpty()) return base + ":" + args;
            return base;
        }
    }

    /** Parses the part before any '|' filters. */
    private static Parts parseBase(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Support bracket args style: namespace.key[args]
        String bracketArgs = "";
        int lb = s.indexOf('[');
        if (lb >= 0) {
            int rb = s.lastIndexOf(']');
            if (rb > lb) {
                bracketArgs = s.substring(lb + 1, rb);
                s = s.substring(0, lb);
            }
        }

        String args = "";
        int colon = s.indexOf(':');
        if (colon >= 0) {
            args = s.substring(colon + 1).trim();
            s = s.substring(0, colon).trim();
        }
        if ((args == null || args.isEmpty()) && bracketArgs != null && !bracketArgs.isEmpty()) {
            args = bracketArgs.trim();
        }

        // Shorthands
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("player")) s = "player.name";
        if (lower.equals("server")) s = "server.name";
        if (lower.equals("world")) s = "world.name";

        // Bare identifiers ({price}, {state_label}) are template variables -> {data.<name>}.
        if (s.indexOf('.') < 0 && BARE_KEY.matcher(s).matches()) {
            return new Parts("data", s, args == null ? "" : args);
        }

        int dot = s.indexOf('.');
        if (dot <= 0 || dot >= s.length() - 1) return null;

        String ns = s.substring(0, dot).trim();
        String key = s.substring(dot + 1).trim();
        if (ns.isEmpty() || key.isEmpty()) return null;
        return new Parts(ns, key, args == null ? "" : args);
    }

    private static String resolve(String namespace, String key, String args, PHContext ctx) {
        if (namespace == null || namespace.isBlank()) return "";
        String ns = normalizeNamespace(namespace);
        String k = key == null ? "" : key;
        String a = args == null ? "" : args;
        boolean clientSide = ctx != null && ctx.level() != null && ctx.level().isClientSide;

        // Client-side: prefer remote resolver so server-only values can be displayed.
        try {
            if (clientSide && REMOTE != null) {
                String remote = REMOTE.resolve(ns, k, a, ctx);
                if (remote != null) return remote;
            }
        } catch (Throwable ignored) {}

        // If no args were provided, allow holograms to "bind" an identifier for a namespace or key.
        // IMPORTANT: we apply this *after* remote lookup on the client so cache keys remain stable.
        // Example:
        //  /holo <id> meta add estate testprop
        //  {estate.property.price}  -> provider receives args="testprop"
        if ((a == null || a.isEmpty()) && ctx != null && ctx.holo() != null) {
            String bind = getBindingFor(ctx, ns, k);
            if (bind != null && !bind.isEmpty()) a = bind;
        }

        PlaceholderProvider provider = REGISTRY.get(ns);
        if (provider == null) return "";
        try {
            String v = provider.resolve(k, a, ctx);
            return v == null ? "" : v;
        } catch (Throwable ignored) {
            return "";
        }
    }
}
