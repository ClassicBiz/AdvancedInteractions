package com.luminacity.advancedinteractions.panel;

/**
 * Shared panel coordinate system constants.
 * Safe to use on both server and client.
 *
 * - Panel coordinates are in "panel pixels".
 * - Commands that ask for width/height in grid units use these for conversion.
 */
public final class PanelUnits {
    private PanelUnits() {}

    /** One grid row height in panel pixels. */
    public static final int ROW_PX = 12;

    /** One grid column width in panel pixels. */
    public static final int COL_PX = 16;
}
