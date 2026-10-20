package com.luminacity.advancedinteractions.api;

import com.luminacity.advancedinteractions.AdvancedInteractions;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Register placeholders without a compile-time dependency on AdvancedInteractions.
 *
 * <p>Send during {@code InterModEnqueueEvent}. The payload is a {@code Map<String, Object>} built only
 * from JDK and Minecraft types:</p>
 * <pre>
 * InterModComms.sendTo("advancedinteractions", "register_placeholder", () -&gt; Map.of(
 *     "namespace",   "mymod",
 *     "key",         "balance",                 // optional: omit to handle the whole namespace
 *     "description", "Player bank balance",     // optional
 *     "clientSide",  false,                      // optional, default false (resolved on the server)
 *     "resolver",    (Function&lt;Map&lt;String, Object&gt;, String&gt;) ctx -&gt; ...));
 * </pre>
 *
 * <p>The resolver's context map holds: {@code "key"} (String, the key after the namespace),
 * {@code "args"} (String, may be empty), {@code "viewer"} (Player, may be null),
 * {@code "level"} (Level, may be null), {@code "holoId"} (UUID, may be null),
 * {@code "holoAlias"} (String, may be null) and {@code "meta"} (read-only Map&lt;String, String&gt;).</p>
 */
public final class PlaceholderIMC {
    private PlaceholderIMC() {}

    public static final String METHOD_REGISTER = "register_placeholder";
    private static final Logger LOG = LoggerFactory.getLogger("AdvancedInteractions");

    @SuppressWarnings("unchecked")
    public static void process(InterModProcessEvent evt) {
        InterModComms.getMessages(AdvancedInteractions.MODID, METHOD_REGISTER::equals).forEach(msg -> {
            try {
                Object payload = msg.messageSupplier().get();
                if (!(payload instanceof Map<?, ?> m)) {
                    LOG.warn("{} sent {} with a non-Map payload; ignored.", msg.senderModId(), METHOD_REGISTER);
                    return;
                }
                String ns = m.get("namespace") instanceof String s ? s : null;
                String key = m.get("key") instanceof String s ? s : null;
                String desc = m.get("description") instanceof String s ? s : "";
                boolean clientSide = Boolean.TRUE.equals(m.get("clientSide"));
                if (ns == null || ns.isBlank() || !(m.get("resolver") instanceof Function<?, ?> fn)) {
                    LOG.warn("{} sent {} without a namespace or resolver; ignored.", msg.senderModId(), METHOD_REGISTER);
                    return;
                }
                Function<Map<String, Object>, String> resolver = (Function<Map<String, Object>, String>) fn;

                if (key != null && !key.isBlank()) {
                    Placeholders.registerKey(ns, key, desc, clientSide,
                            (args, ctx) -> resolver.apply(contextMap(key, args, ctx)));
                } else {
                    Placeholders.register(ns, new Placeholders.PlaceholderProvider() {
                        @Override public String resolve(String k, String args, Placeholders.PHContext ctx) {
                            return resolver.apply(contextMap(k, args, ctx));
                        }
                        @Override public boolean isClientSide(String k) { return clientSide; }
                    });
                }
                LOG.info("Registered placeholder {}{} from {}", ns, key == null ? ".*" : "." + key, msg.senderModId());
            } catch (Throwable t) {
                LOG.warn("Bad {} message from {}: {}", METHOD_REGISTER, msg.senderModId(), t.toString());
            }
        });
    }

    private static Map<String, Object> contextMap(String key, String args, Placeholders.PHContext ctx) {
        Map<String, Object> m = new HashMap<>();
        m.put("key", key == null ? "" : key);
        m.put("args", args == null ? "" : args);
        if (ctx != null) {
            m.put("viewer", ctx.viewer());
            m.put("level", ctx.level());
            if (ctx.holo() != null) {
                m.put("holoId", ctx.holo().id);
                m.put("holoAlias", ctx.holo().alias);
                m.put("meta", ctx.holo().meta == null ? Map.of() : java.util.Collections.unmodifiableMap(ctx.holo().meta));
            }
        }
        return m;
    }
}
