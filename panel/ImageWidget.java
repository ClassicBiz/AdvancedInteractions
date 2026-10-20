package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

/**
 * A picture on the panel.
 *
 * <p>{@code source} is either a server image name ("logo" or "logo.png", from
 * config/advancedinteractions/images/ on the server, sent to clients automatically) or a resource-pack
 * texture ("minecraft:textures/block/stone.png", or "mypack:holo/logo" which means
 * "mypack:textures/holo/logo.png").</p>
 */
public class ImageWidget implements PanelWidget {
    private String id;
    private double x, y, w = 32, h = 32;
    private String source = "";
    private int tint = 0xFFFFFFFF;
    private float scale = 1f;
    private float rotationDeg = 0f;
    private int layer = 0;

    private String visPerm = "";
    private int visOp = -1;
    private String condition = "";
    private WidgetAnim anim = new WidgetAnim();
    private ClickSettings click = new ClickSettings();
    private boolean startHidden = false;

    @Override public ClickSettings interaction() { return click; }
    @Override public boolean startHidden() { return startHidden; }
    @Override public void setStartHidden(boolean b) { this.startHidden = b; }

    public ImageWidget() {}

    public ImageWidget(String id, double x, double y, double w, double h, String source) {
        this.id = id; this.x = x; this.y = y; this.w = w; this.h = h; this.source = source == null ? "" : source;
    }

    public String source() { return source; }
    public void setSource(String s) { this.source = s == null ? "" : s.trim(); }
    /** Colour multiplied into the image (white = unchanged); its alpha is the image's opacity. */
    public int tint() { return tint; }
    public void setTint(int argb) { this.tint = argb; }
    public float scale() { return scale; }
    public void setScale(float s) { this.scale = Math.max(0.01f, s); }
    public int layer() { return layer; }
    public void setLayer(int l) { this.layer = l; }

    @Override public WidgetAnim anim() { return anim; }
    @Override public String visPerm() { return visPerm; }
    @Override public void setVisPerm(String perm) { this.visPerm = perm == null ? "" : perm; }
    @Override public int visOp() { return visOp; }
    @Override public void setVisOp(int op) { this.visOp = op; }
    @Override public String condition() { return condition; }
    @Override public void setCondition(String expr) { this.condition = expr == null ? "" : expr.trim(); }

    @Override public String id() { return id; }
    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double w() { return w; }
    @Override public double h() { return h; }
    @Override public void setPos(double x, double y) { this.x = x; this.y = y; }
    @Override public void setSize(double w, double h) { this.w = Math.max(1, w); this.h = Math.max(1, h); }
    @Override public float rotation() { return rotationDeg; }
    @Override public void setRotation(float deg) { this.rotationDeg = deg; }
    @Override public boolean hit(double px, double py) { return px >= x && py >= y && px <= x + w * scale && py <= y + h * scale; }
    @Override public String kind() { return "image"; }

    @Override public void saveTo(CompoundTag out) {
        out.putString("kind", "image");
        out.putString("id", id);
        out.putDouble("x", x); out.putDouble("y", y);
        out.putDouble("w", w); out.putDouble("h", h);
        out.putString("src", source);
        if (tint != 0xFFFFFFFF) out.putInt("tint", tint);
        if (scale != 1f) out.putFloat("scale", scale);
        if (rotationDeg != 0f) out.putFloat("rot", rotationDeg);
        if (layer != 0) out.putInt("layer", layer);
        if (!visPerm.isBlank()) out.putString("visPerm", visPerm);
        if (visOp >= 0) out.putInt("visOp", visOp);
        if (!condition.isBlank()) out.putString("cond", condition);
        anim.saveTo(out);
        if (!click.isDefault()) { CompoundTag c = new CompoundTag(); click.saveTo(c); out.put("click", c); }
        if (startHidden) out.putBoolean("startHidden", true);
    }

    @Override public void loadFrom(CompoundTag in) {
        id = in.getString("id");
        x = in.getDouble("x"); y = in.getDouble("y");
        w = in.contains("w") ? in.getDouble("w") : 32;
        h = in.contains("h") ? in.getDouble("h") : 32;
        source = in.getString("src");
        tint = in.contains("tint") ? in.getInt("tint") : 0xFFFFFFFF;
        scale = in.contains("scale") ? in.getFloat("scale") : 1f;
        rotationDeg = in.getFloat("rot");
        layer = in.getInt("layer");
        visPerm = in.getString("visPerm");
        visOp = in.contains("visOp") ? in.getInt("visOp") : -1;
        condition = in.getString("cond");
        anim = WidgetAnim.load(in);
        click = ClickSettings.load(in.contains("click") ? in.getCompound("click") : null);
        startHidden = in.getBoolean("startHidden");
    }
}
