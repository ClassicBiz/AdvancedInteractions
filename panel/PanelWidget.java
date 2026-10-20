
package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

public interface PanelWidget {
    String id();
    double x(); double y(); double w(); double h();
    void setPos(double x, double y);
    void setSize(double w, double h);
    boolean hit(double px, double py);

    // NEW: rotation in degrees (panel plane, clockwise)
    default float rotation() { return 0.0f; }
    default void setRotation(float deg) {}

    // NEW: per-widget visibility lock (server-side filtering)
    // If both are set, either one may grant visibility.
    default String visPerm() { return ""; }
    default void setVisPerm(String perm) {}
    default int visOp() { return -1; }
    default void setVisOp(int op) {}

    // Optional live visibility condition evaluated server-side per viewer,
    // e.g. "has(price)", "!has(owner) && perm(adv.holo.vip)". See util.ConditionEval.
    default String condition() { return ""; }
    default void setCondition(String expr) {}

    /** Animation (never null for built-in widgets; null means "not animatable"). */
    default WidgetAnim anim() { return null; }

    /** Click behaviour; any element can be made clickable (buttons always are). Null = not supported. */
    default ClickSettings interaction() { return null; }

    /** Hidden until an advholo:show action reveals it for a player. */
    default boolean startHidden() { return false; }
    default void setStartHidden(boolean hidden) {}

    void saveTo(CompoundTag out);
    void loadFrom(CompoundTag in);
    String kind();
}
