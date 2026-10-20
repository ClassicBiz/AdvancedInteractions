package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * How a button looks in each state. The defaults reproduce the original flat square button, so
 * existing buttons are unchanged. The fill colour itself stays in {@link ButtonWidget#color()}.
 */
public final class ButtonStyle {
    public enum Shape { SQUARE, ROUNDED, PILL, CIRCLE }
    public enum Fill { SOLID, OUTLINE, TRANSPARENT, GRADIENT }
    public enum IconPos { LEFT, RIGHT, ONLY }

    public Shape shape = Shape.SQUARE;
    /** Corner radius in panel pixels (ROUNDED). */
    public float radius = 4f;
    public Fill fill = Fill.SOLID;
    /** Bottom colour of a GRADIENT fill (top is the button colour). */
    public int gradientTo = 0xFF1A4FA0;
    public float borderWidth = 0f;
    public int borderColor = 0xFFFFFFFF;

    /** Soft halo around the button while hovered. */
    public boolean hoverGlow = false;
    /** Size while hovered (1 = no change). */
    public float hoverScale = 1f;
    /** Colour while pressed; 0 = a white flash. */
    public int pressedColor = 0;
    /** Size while pressed. */
    public float pressedScale = 0.93f;
    /** Colour while disabled; 0 = automatic grey-out. */
    public int disabledColor = 0;

    /** Item id ("minecraft:diamond") or texture ("ns:textures/....png" / server image name); empty = none. */
    public String icon = "";
    public IconPos iconPos = IconPos.LEFT;
    public float iconSize = 12f;

    public boolean isDefault() {
        CompoundTag t = new CompoundTag();
        saveTo(t);
        return t.isEmpty();
    }

    /** Icon is an item (vs a texture)? Items have no "textures/" path and no .png. */
    public boolean iconIsItem() {
        return !icon.isBlank() && !icon.contains("/") && !icon.endsWith(".png") && icon.indexOf(':') > 0;
    }

    public ButtonStyle copy() {
        CompoundTag t = new CompoundTag();
        saveTo(t);
        return load(t);
    }

    public void saveTo(CompoundTag t) {
        if (shape != Shape.SQUARE) t.putString("shape", shape.name().toLowerCase(Locale.ROOT));
        if (radius != 4f) t.putFloat("radius", radius);
        if (fill != Fill.SOLID) t.putString("fill", fill.name().toLowerCase(Locale.ROOT));
        if (gradientTo != 0xFF1A4FA0) t.putInt("gradTo", gradientTo);
        if (borderWidth > 0) { t.putFloat("borderW", borderWidth); t.putInt("borderC", borderColor); }
        if (hoverGlow) t.putBoolean("glow", true);
        if (hoverScale != 1f) t.putFloat("hoverScale", hoverScale);
        if (pressedColor != 0) t.putInt("pressColor", pressedColor);
        if (pressedScale != 0.93f) t.putFloat("pressScale", pressedScale);
        if (disabledColor != 0) t.putInt("disColor", disabledColor);
        if (!icon.isBlank()) {
            t.putString("icon", icon);
            if (iconPos != IconPos.LEFT) t.putString("iconPos", iconPos.name().toLowerCase(Locale.ROOT));
            if (iconSize != 12f) t.putFloat("iconSize", iconSize);
        }
    }

    public static ButtonStyle load(CompoundTag t) {
        ButtonStyle s = new ButtonStyle();
        if (t == null) return s;
        s.shape = parse(Shape.class, t.getString("shape"), Shape.SQUARE);
        if (t.contains("radius")) s.radius = t.getFloat("radius");
        s.fill = parse(Fill.class, t.getString("fill"), Fill.SOLID);
        if (t.contains("gradTo")) s.gradientTo = t.getInt("gradTo");
        s.borderWidth = t.getFloat("borderW");
        if (t.contains("borderC")) s.borderColor = t.getInt("borderC");
        s.hoverGlow = t.getBoolean("glow");
        if (t.contains("hoverScale")) s.hoverScale = t.getFloat("hoverScale");
        s.pressedColor = t.getInt("pressColor");
        if (t.contains("pressScale")) s.pressedScale = t.getFloat("pressScale");
        s.disabledColor = t.getInt("disColor");
        s.icon = t.getString("icon");
        s.iconPos = parse(IconPos.class, t.getString("iconPos"), IconPos.LEFT);
        if (t.contains("iconSize")) s.iconSize = t.getFloat("iconSize");
        return s;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String s, E fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT)); } catch (Exception e) { return fallback; }
    }
}
