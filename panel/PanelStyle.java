package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

public class PanelStyle {
    public int bg = 0x22000000; // lighter, matches /holo addline vibe // lighter default (was 0xAA000000)
    public int radius = 6;
    public int padding = 6;
    public float uiScale = 1.0f;
    public float brightness = 1.0f; // 1.0 normal; <1 dim; >1 boost
    /** Rounded background corners in panel px (0 = square). ("radius" above is legacy and unused.) */
    public float cornerRadius = 0f;
    /** Border around the panel in panel px (0 = none). */
    public float borderWidth = 0f;
    public int borderColor = 0xFFFFFFFF;

    public void saveTo(CompoundTag t) {
        t.putInt("bg", bg);
        t.putInt("radius", radius);
        t.putInt("padding", padding);
        t.putFloat("uiScale", uiScale);
        t.putFloat("brightness", brightness);
        if (cornerRadius > 0) t.putFloat("corner", cornerRadius);
        if (borderWidth > 0) { t.putFloat("borderW", borderWidth); t.putInt("borderC", borderColor); }
    }
    public static PanelStyle load(CompoundTag t) {
        PanelStyle s = new PanelStyle();
        if (t.contains("bg")) s.bg = t.getInt("bg");
        if (t.contains("radius")) s.radius = t.getInt("radius");
        if (t.contains("padding")) s.padding = t.getInt("padding");
        if (t.contains("uiScale")) s.uiScale = t.getFloat("uiScale");
        if (t.contains("brightness")) s.brightness = t.getFloat("brightness");
        s.cornerRadius = t.getFloat("corner");
        s.borderWidth = t.getFloat("borderW");
        if (t.contains("borderC")) s.borderColor = t.getInt("borderC");
        return s;
    }
}

