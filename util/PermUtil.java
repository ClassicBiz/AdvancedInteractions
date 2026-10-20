package com.luminacity.advancedinteractions.util;

import net.minecraft.server.level.ServerPlayer;

/**
 * Permission helper that optionally uses LuckPerms via reflection.
 *
 * <p>No hard dependency on LuckPerms: if it's not installed, this simply returns false
 * (callers can fall back to vanilla op checks).</p>
 */
public final class PermUtil {
    private PermUtil() {}

    // Only positive results are cached: LuckPerms' API becomes available at server start,
    // so an early "not loaded" answer must not stick.
    private static volatile boolean luckPermsSeen = false;

    /** True once the LuckPerms API is reachable. */
    public static boolean isLuckPermsLoaded() {
        if (luckPermsSeen) return true;
        try {
            Class<?> providerClz = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = providerClz.getMethod("get").invoke(null);
            if (lp != null) luckPermsSeen = true;
        } catch (Throwable ignored) {}
        return luckPermsSeen;
    }

    /**
     * Combined lock check used by views, pages and widgets.
     *
     * <ul>
     *   <li>No perm and no op level configured: open.</li>
     *   <li>Either the LuckPerms node OR the vanilla op level grants access.</li>
     *   <li>Without LuckPerms installed, a perm-only lock falls back to op level 2.</li>
     * </ul>
     */
    public static boolean check(ServerPlayer player, String perm, int opLevel) {
        boolean hasPerm = perm != null && !perm.isBlank();
        boolean hasOp = opLevel >= 0;
        if (!hasPerm && !hasOp) return true;
        if (player == null) return false;
        if (hasPerm && hasPermission(player, perm.trim())) return true;
        if (hasOp && player.hasPermissions(Math.min(4, opLevel))) return true;
        return hasPerm && !hasOp && !isLuckPermsLoaded() && player.hasPermissions(2);
    }

    /**
     * Returns true if the player has the given permission node in LuckPerms.
     * If LuckPerms is missing or something fails, returns false.
     */
    /**
     * Cached reflection handles against LuckPerms' public API interfaces, resolved once.
     * Falls back to the slower discovery path below if anything is missing.
     */
    private static final class Fast {
        private static volatile boolean ready = false;
        private static volatile boolean failed = false;
        private static Object lp;
        private static java.lang.reflect.Method getUserManager, getUser, getContextManager, getQueryOptions,
                getStaticQueryOptions, getCachedData, getPermissionData, checkPermission, asBoolean;

        private static synchronized boolean init() {
            if (ready) return true;
            if (failed) return false;
            try {
                Object api = Class.forName("net.luckperms.api.LuckPermsProvider").getMethod("get").invoke(null);
                if (api == null) return false;
                Class<?> lpI = Class.forName("net.luckperms.api.LuckPerms");
                Class<?> umI = Class.forName("net.luckperms.api.model.user.UserManager");
                Class<?> cmI = Class.forName("net.luckperms.api.context.ContextManager");
                Class<?> userI = Class.forName("net.luckperms.api.model.user.User");
                Class<?> cdmI = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
                Class<?> cpdI = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
                Class<?> qoI = Class.forName("net.luckperms.api.query.QueryOptions");
                Class<?> triI = Class.forName("net.luckperms.api.util.Tristate");
                getUserManager = lpI.getMethod("getUserManager");
                getContextManager = lpI.getMethod("getContextManager");
                getUser = umI.getMethod("getUser", java.util.UUID.class);
                getQueryOptions = cmI.getMethod("getQueryOptions", userI);
                getStaticQueryOptions = cmI.getMethod("getStaticQueryOptions");
                getCachedData = userI.getMethod("getCachedData");
                getPermissionData = cdmI.getMethod("getPermissionData", qoI);
                checkPermission = cpdI.getMethod("checkPermission", String.class);
                asBoolean = triI.getMethod("asBoolean");
                lp = api;
                ready = true;
                luckPermsSeen = true;
                return true;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                failed = true; // LuckPerms absent or an incompatible API: stop retrying
                return false;
            } catch (Throwable t) {
                return false; // API not loaded yet: retry later
            }
        }

        /** @return the answer, or null if the fast path can't decide (caller falls back). */
        static Boolean check(ServerPlayer player, String node) {
            if (!ready && !init()) return null;
            try {
                Object um = getUserManager.invoke(lp);
                Object user = um == null ? null : getUser.invoke(um, player.getUUID());
                if (user == null) return null;
                Object cm = getContextManager.invoke(lp);
                Object qo = null;
                if (cm != null) {
                    Object opt = getQueryOptions.invoke(cm, user);
                    if (opt instanceof java.util.Optional<?> o && o.isPresent()) qo = o.get();
                    if (qo == null) qo = getStaticQueryOptions.invoke(cm);
                }
                if (qo == null) return null;
                Object perm = getPermissionData.invoke(getCachedData.invoke(user), qo);
                Object tri = checkPermission.invoke(perm, node);
                Object b = asBoolean.invoke(tri);
                return b instanceof Boolean bb ? bb : Boolean.FALSE;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    public static boolean hasPermission(ServerPlayer player, String node) {
        if (player == null || node == null || node.isBlank()) return false;

        Boolean fast = Fast.check(player, node);
        if (fast != null) return fast;

        try {
            // LuckPermsProvider.get()
            Class<?> providerClz = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = providerClz.getMethod("get").invoke(null);
            if (lp == null) return false;

            // -----------------------------------------------------------------
            // Resolve the LuckPerms User for this player
            // -----------------------------------------------------------------
            Object user = null;
            try {
                // Preferred path on LP v5: lp.getUserManager().getUser(uuid)
                Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
                if (um != null) {
                    user = um.getClass().getMethod("getUser", java.util.UUID.class).invoke(um, player.getUUID());
                    // If not cached yet, opportunistically start a load and use if already completed.
                    if (user == null) {
                        try {
                            Object fut = um.getClass().getMethod("loadUser", java.util.UUID.class).invoke(um, player.getUUID());
                            if (fut instanceof java.util.concurrent.CompletableFuture<?> cf) {
                                user = cf.getNow(null);
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            try {
                // Modern LP on some platforms: lp.getPlayerAdapter(ServerPlayer.class).getUser(player)
                Object adapter = lp.getClass().getMethod("getPlayerAdapter", Class.class).invoke(lp, ServerPlayer.class);
                if (adapter != null) {
                    user = adapter.getClass().getMethod("getUser", ServerPlayer.class).invoke(adapter, player);
                }
            } catch (Throwable ignored) {}

            if (user == null) {
                // Fallback: lp.getUserManager().getUser(player.getUUID())
                try {
                    Object um = lp.getClass().getMethod("getUserManager").invoke(lp);
                    if (um != null) {
                        user = um.getClass().getMethod("getUser", java.util.UUID.class).invoke(um, player.getUUID());
                    }
                } catch (Throwable ignored) {}
            }

            if (user == null) return false;

            // -----------------------------------------------------------------
            // QueryOptions (contexts) — required by LuckPerms v5+ to evaluate
            // permissions correctly on most platforms.
            // -----------------------------------------------------------------
            Object queryOptions = null;
            try {
                Object ctxMgr = lp.getClass().getMethod("getContextManager").invoke(lp);
                if (ctxMgr != null) {
                    Object opt = null;
                    try {
                        opt = ctxMgr.getClass().getMethod("getQueryOptions", user.getClass()).invoke(ctxMgr, user);
                    } catch (Throwable ignored) {
                        // Some builds type the param as User interface; try loading it directly.
                        try {
                            Class<?> userClz = Class.forName("net.luckperms.api.model.user.User");
                            opt = ctxMgr.getClass().getMethod("getQueryOptions", userClz).invoke(ctxMgr, user);
                        } catch (Throwable ignored2) {}
                    }

                    if (opt != null) {
                        try {
                            // Optional<QueryOptions>
                            boolean present = (boolean) opt.getClass().getMethod("isPresent").invoke(opt);
                            if (present) queryOptions = opt.getClass().getMethod("get").invoke(opt);
                        } catch (Throwable ignored) {}
                    }

                    if (queryOptions == null) {
                        try {
                            queryOptions = ctxMgr.getClass().getMethod("getStaticQueryOptions").invoke(ctxMgr);
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}

            Object cached = user.getClass().getMethod("getCachedData").invoke(user);

            // LP v5: cached.getPermissionData(QueryOptions)
            Object permData = null;
            if (queryOptions != null) {
                try {
                    permData = cached.getClass().getMethod("getPermissionData", queryOptions.getClass()).invoke(cached, queryOptions);
                } catch (Throwable ignored) {
                    try {
                        Class<?> qoClz = Class.forName("net.luckperms.api.query.QueryOptions");
                        permData = cached.getClass().getMethod("getPermissionData", qoClz).invoke(cached, queryOptions);
                    } catch (Throwable ignored2) {}
                }
            }

            // Older/alternate builds: cached.getPermissionData()
            if (permData == null) {
                try {
                    permData = cached.getClass().getMethod("getPermissionData").invoke(cached);
                } catch (Throwable ignored) {}
            }

            if (permData == null) return false;

            Object result = permData.getClass().getMethod("checkPermission", String.class).invoke(permData, node);

            // result.asBoolean()
            Object b = result.getClass().getMethod("asBoolean").invoke(result);
            if (b instanceof Boolean bb) return bb;
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Registers a LuckPerms user recalculation listener (if LuckPerms is present) and invokes
     * {@code onChange} with the affected player's UUID.
     *
     * <p>Implemented via reflection to avoid a hard LuckPerms dependency. Safe to call multiple
     * times; if LuckPerms isn't installed, this is a no-op.</p>
     */
    public static boolean registerRecalcListener(java.util.function.Consumer<java.util.UUID> onChange) {
        if (onChange == null) return false;
        try {
            Class<?> providerClz = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = providerClz.getMethod("get").invoke(null);
            if (lp == null) return false;
            luckPermsSeen = true;

            // eventBus = lp.getEventBus()
            Object eventBus = lp.getClass().getMethod("getEventBus").invoke(lp);
            if (eventBus == null) return false;

            // Subscribe to both event types (platform/build differences)
            Class<?> recalcEvt = null;
            Class<?> loadEvt = null;
            try { recalcEvt = Class.forName("net.luckperms.api.event.user.UserDataRecalculateEvent"); } catch (Throwable ignored) {}
            try { loadEvt   = Class.forName("net.luckperms.api.event.user.UserLoadEvent"); } catch (Throwable ignored) {}
            if (recalcEvt == null && loadEvt == null) return false;

            java.util.function.Consumer<Object> consumer = (evt) -> {
                if (evt == null) return;
                try {
                    // evt.getUser() -> User
                    Object user = null;
                    try {
                        user = evt.getClass().getMethod("getUser").invoke(evt);
                    } catch (Throwable ignored3) {}

                    java.util.UUID uuid = null;
                    if (user != null) {
                        try {
                            Object u = user.getClass().getMethod("getUniqueId").invoke(user);
                            if (u instanceof java.util.UUID id) uuid = id;
                        } catch (Throwable ignored4) {}
                    }

                    if (uuid == null) {
                        try {
                            Object u = evt.getClass().getMethod("getUniqueId").invoke(evt);
                            if (u instanceof java.util.UUID id) uuid = id;
                        } catch (Throwable ignored4) {}
                    }

                    if (uuid != null) onChange.accept(uuid);
                } catch (Throwable ignored3) {}
            };

            // eventBus.subscribe(Class, Consumer) OR subscribe(plugin, Class, Consumer)
            java.lang.reflect.Method sub2 = null;
            java.lang.reflect.Method sub3 = null;
            for (var m : eventBus.getClass().getMethods()) {
                if (!m.getName().equals("subscribe")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == Class.class && java.util.function.Consumer.class.isAssignableFrom(p[1])) {
                    sub2 = m;
                } else if (p.length == 3 && p[1] == Class.class && java.util.function.Consumer.class.isAssignableFrom(p[2])) {
                    sub3 = m;
                }
            }
            if (sub2 == null && sub3 == null) return false;

            if (recalcEvt != null) {
                if (sub2 != null) sub2.invoke(eventBus, recalcEvt, consumer);
                else sub3.invoke(eventBus, lp, recalcEvt, consumer);
            }
            if (loadEvt != null) {
                if (sub2 != null) sub2.invoke(eventBus, loadEvt, consumer);
                else sub3.invoke(eventBus, lp, loadEvt, consumer);
            }
            return true;
        } catch (Throwable ignored) {
            // LuckPerms missing or not loaded yet (it loads at server start)
            return false;
        }
    }
}
