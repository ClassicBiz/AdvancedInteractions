package com.luminacity.advancedinteractions.api.datasource;

import java.util.Map;

/**
 * Reads values from a block a hologram is linked to, for {@code {link.SOURCE.KEY:LINK[,ARG]}} placeholders,
 * e.g. {@code {link.container.count:chest,minecraft:diamond}}. Register with {@link DataSources#register}.
 * Called on the server thread, only while the linked block's chunk is loaded.
 */
public interface DataSource {
    /**
     * @param key what to read ("count", "progress"...)
     * @param arg extra argument after the link name ("" when none)
     * @return the value, or null when this source doesn't apply to that block
     */
    String get(String key, String arg, LinkContext ctx);

    /** Whether this source can read the block (e.g. it is a container). Unavailable links show "". */
    default boolean available(LinkContext ctx) { return true; }

    /** Documented keys -> description (editor and /aholo placeholders). */
    default Map<String, String> keys() { return Map.of(); }
}
