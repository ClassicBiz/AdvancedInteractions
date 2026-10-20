
package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

public class TextWidget implements PanelWidget {
    private String id, text = "";
    private double x, y, w, h;
    private float scale = 1.0f;
    private int color = 0xFFFFFFFF;
    private boolean bold = false;
    private float rotationDeg = 0.0f;

    // Optional visibility lock
    private String visPerm = "";
    private int visOp = -1;
    private String condition = "";
    private WidgetAnim anim = new WidgetAnim();
    private ClickSettings click = new ClickSettings();
    private boolean startHidden = false;

    @Override public ClickSettings interaction() { return click; }
    @Override public boolean startHidden() { return startHidden; }
    @Override public void setStartHidden(boolean b) { this.startHidden = b; }

    @Override public WidgetAnim anim() { return anim; }

    /** Horizontal alignment relative to x: "left" (x is the left edge), "center" or "right". */
    private String align = "left";

    public TextWidget() {}
    public TextWidget(String id, double x, double y, String text) {
        this.id = id; this.x = x; this.y = y; this.text = text;
    }

    public String text() { return text; }
    public void setText(String t) { this.text = t; }
    public float scale() { return scale; }
    public void setScale(float s) { this.scale = s; }
    public int color() { return color; }
    public void setColor(int c) { this.color = c; }
    public boolean bold() { return bold; }
    public void setBold(boolean b) { this.bold = b; }

    @Override public String visPerm() { return visPerm; }
    @Override public void setVisPerm(String perm) { this.visPerm = perm == null ? "" : perm; }
    @Override public int visOp() { return visOp; }
    @Override public void setVisOp(int op) { this.visOp = op; }
    @Override public String condition() { return condition; }
    @Override public void setCondition(String expr) { this.condition = expr == null ? "" : expr.trim(); }

    public String align() { return align; }
    public void setAlign(String a) {
        String v = a == null ? "left" : a.trim().toLowerCase(java.util.Locale.ROOT);
        this.align = (v.equals("center") || v.equals("centre") || v.equals("middle")) ? "center"
                : v.equals("right") ? "right" : "left";
    }

    @Override public String id() { return id; }
    @Override public double x() { return x; }
    @Override public double y() { return y; }
    @Override public double w() { return w; }
    @Override public double h() { return h; }
    @Override public void setPos(double x, double y) { this.x = x; this.y = y; }
    @Override public void setSize(double w, double h) { this.w = w; this.h = h; }
    @Override public float rotation() { return rotationDeg; }
    @Override public void setRotation(float deg) { this.rotationDeg = deg; }
    @Override public boolean hit(double px, double py) { return px>=x && py>=y; }
    @Override public String kind() { return "text"; }

    @Override public void saveTo(CompoundTag out) {
        out.putString("kind", "text");
        out.putString("id", id);
        out.putDouble("x", x); out.putDouble("y", y);
        if (w != 0) out.putDouble("w", w);
        if (h != 0) out.putDouble("h", h);
        out.putString("text", text);
        out.putFloat("scale", scale);
        out.putInt("color", color);
        out.putBoolean("bold", bold);
        if (rotationDeg != 0.0f) out.putFloat("rot", rotationDeg);

        if (visPerm != null && !visPerm.isBlank()) out.putString("visPerm", visPerm);
        if (visOp >= 0) out.putInt("visOp", visOp);
        if (condition != null && !condition.isBlank()) out.putString("cond", condition);
        anim.saveTo(out);
        if (!click.isDefault()) { CompoundTag c = new CompoundTag(); click.saveTo(c); out.put("click", c); }
        if (startHidden) out.putBoolean("startHidden", true);
        if (align != null && !align.equals("left")) out.putString("align", align);
    }

    @Override public void loadFrom(CompoundTag in) {
        id = in.getString("id");
        x = in.getDouble("x"); y = in.getDouble("y");
        w = in.getDouble("w"); h = in.getDouble("h");
        text = in.getString("text");
        scale = in.contains("scale") ? in.getFloat("scale") : 1.0f;
        color = in.contains("color") ? in.getInt("color") : 0xFFFFFFFF;
        bold = in.contains("bold") && in.getBoolean("bold");
        rotationDeg = in.contains("rot") ? in.getFloat("rot") : 0.0f;

        visPerm = in.contains("visPerm") ? in.getString("visPerm") : "";
        visOp = in.contains("visOp") ? in.getInt("visOp") : -1;
        condition = in.contains("cond") ? in.getString("cond") : "";
        anim = WidgetAnim.load(in);
        click = ClickSettings.load(in.contains("click") ? in.getCompound("click") : null);
        startHidden = in.getBoolean("startHidden");
        setAlign(in.contains("align") ? in.getString("align") : "left");
    }
}
