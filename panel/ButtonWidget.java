package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

public class ButtonWidget implements PanelWidget {
    private String id, label = "", actionId = "";
    private double x, y, w, h;
    private double boundsW = 0, boundsH = 0; // optional clickable bounds override (panel pixels)
    private float scale = 1.0f;
    private int color = 0x00000000;
    private int colorHover = 0x00000000;
    private float rotationDeg = 0.0f;

    // Optional visibility lock
    private String visPerm = "";
    private int visOp = -1;
    private String condition = "";
    private WidgetAnim anim = new WidgetAnim();
    private boolean startHidden = false;
    private ButtonStyle style = new ButtonStyle();

    /** Shape, fill, border, hover/pressed/disabled looks and icon. Never null. */
    public ButtonStyle style() { return style; }
    public void setStyle(ButtonStyle s) { this.style = s == null ? new ButtonStyle() : s; }

    @Override public boolean startHidden() { return startHidden; }
    @Override public void setStartHidden(boolean b) { this.startHidden = b; }

    @Override public WidgetAnim anim() { return anim; }

    private ClickSettings click = new ClickSettings();

    /** Click types, cooldown, cost and feedback. Never null. */
    public ClickSettings click() { return click; }

    @Override public ClickSettings interaction() { return click; }


    public ButtonWidget() {}
    // New ctor: auto-size by label; w/h are ignored and remain 0
    public ButtonWidget(String id, double x, double y, String actionId, String label) {
        this.id = id; this.x = x; this.y = y; this.w = 0; this.h = 0; this.actionId = actionId; this.label = label;
    }
    public ButtonWidget(String id, double x, double y, double w, double h, String actionId, String label) {
        this.id = id; this.x = x; this.y = y; this.w = w; this.h = h; this.actionId = actionId; this.label = label;
    }

    public String label() { return label; }
    public void setLabel(String s) { this.label = s; }
    public String actionId() { return actionId; }
    public void setActionId(String a) { this.actionId = a; }
    public int color() { return color; }
    public void setColor(int c) { this.color = c; }
    public int colorHover() { return colorHover; }
    public void setColorHover(int c) { this.colorHover = c; }

    public float scale() { return scale; }
    public void setScale(float s) { this.scale = s; }

    /** Optional clickable area override. If 0, clickable area matches rendered size. */
    public double boundsW() { return boundsW; }
    public double boundsH() { return boundsH; }
    public void setBounds(double w, double h) { this.boundsW = w; this.boundsH = h; }
    public void clearBounds() { this.boundsW = 0; this.boundsH = 0; }

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
    @Override public void setSize(double w, double h) { this.w = w; this.h = h; }
    @Override public float rotation() { return rotationDeg; }
    @Override public void setRotation(float deg) { this.rotationDeg = deg; }
    @Override public boolean hit(double px, double py) {
        // Size is defined in panel pixels.
        // Render path applies this widget's scale(), so the hitbox must too.
        double hw = (boundsW > 0 ? boundsW : w);
        double hh = (boundsH > 0 ? boundsH : h);
        if (scale != 1.0f) {
            hw *= scale;
            hh *= scale;
        }

        // Defensive: a zero-sized widget is never clickable.
        // (This can happen if a button was created with auto-size but never measured.)
        if (hw <= 0.0 || hh <= 0.0) return false;

        // If not rotated, normal AABB hit
        if (rotationDeg == 0.0f) {
            return px>=x && py>=y && px<=x+hw && py<=y+hh;
        }

        // Rotate the point into the widget's local (unrotated) space
        double cx = x + hw / 2.0;
        double cy = y + hh / 2.0;

        double rad = Math.toRadians(rotationDeg);
        double cos = Math.cos(-rad);
        double sin = Math.sin(-rad);

        double dx = px - cx;
        double dy = py - cy;

        double lx = dx * cos - dy * sin + cx;
        double ly = dx * sin + dy * cos + cy;

        return lx>=x && ly>=y && lx<=x+hw && ly<=y+hh;
    }
    @Override public String kind() { return "button"; }

    @Override public void saveTo(CompoundTag out) {
        out.putString("kind", "button");
        out.putString("id", id);
        out.putDouble("x", x); out.putDouble("y", y);
        out.putDouble("w", w); out.putDouble("h", h);
        if (boundsW > 0) out.putDouble("bw", boundsW);
        if (boundsH > 0) out.putDouble("bh", boundsH);
        if (scale != 1.0f) out.putFloat("scale", scale);
        if (rotationDeg != 0.0f) out.putFloat("rot", rotationDeg);
        out.putString("label", label);
        out.putString("actionId", actionId);
        out.putInt("color", color);
        out.putInt("colorHover", colorHover);

        if (visPerm != null && !visPerm.isBlank()) out.putString("visPerm", visPerm);
        if (visOp >= 0) out.putInt("visOp", visOp);
        if (condition != null && !condition.isBlank()) out.putString("cond", condition);
        anim.saveTo(out);
        if (startHidden) out.putBoolean("startHidden", true);
        if (!style.isDefault()) { CompoundTag st = new CompoundTag(); style.saveTo(st); out.put("style", st); }
        if (!click.isDefault()) {
            CompoundTag c = new CompoundTag();
            click.saveTo(c);
            out.put("click", c);
        }
    }
    @Override public void loadFrom(CompoundTag in) {
        id = in.getString("id");
        x = in.getDouble("x"); y = in.getDouble("y");
        w = in.getDouble("w"); h = in.getDouble("h");
        boundsW = in.contains("bw") ? in.getDouble("bw") : 0;
        boundsH = in.contains("bh") ? in.getDouble("bh") : 0;
        scale = in.contains("scale") ? in.getFloat("scale") : 1.0f;
        rotationDeg = in.contains("rot") ? in.getFloat("rot") : 0.0f;
        label = in.getString("label");
        actionId = in.getString("actionId");
        color = in.contains("color") ? in.getInt("color") : 0x00000000;
        colorHover = in.contains("colorHover") ? in.getInt("colorHover") : 0x00000000;

        visPerm = in.contains("visPerm") ? in.getString("visPerm") : "";
        visOp = in.contains("visOp") ? in.getInt("visOp") : -1;
        condition = in.contains("cond") ? in.getString("cond") : "";
        anim = WidgetAnim.load(in);
        startHidden = in.getBoolean("startHidden");
        style = ButtonStyle.load(in.contains("style") ? in.getCompound("style") : null);
        click = ClickSettings.load(in.contains("click") ? in.getCompound("click") : null);
    }
}
