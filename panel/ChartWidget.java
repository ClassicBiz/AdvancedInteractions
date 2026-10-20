package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/**
 * A graph of a value over time ({server.tps}, {server.players}, {top.value:kills:1}...).
 *
 * <p>The server samples {@code source} every {@code interval} seconds and keeps the last {@code points}
 * values; viewers receive them like any other placeholder. Sources are evaluated without a viewer, so use
 * server-wide values rather than per-player ones.</p>
 */
public class ChartWidget implements PanelWidget {
    public enum Style { LINE, AREA, BAR }

    private String id;
    private double x, y, w = 96, h = 40;
    private String source = "{server.players}";
    private int interval = 5;
    private int points = 60;
    private Style style = Style.AREA;
    private int lineColor = 0xFF2F81F7;
    private int fillColor = 0x552F81F7;
    private boolean autoMin = true, autoMax = true;
    private double min = 0, max = 20;
    private boolean labels = true;
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

    public ChartWidget() {}

    public ChartWidget(String id, double x, double y, String source) {
        this.id = id; this.x = x; this.y = y; this.source = source;
    }

    public String source() { return source; }
    public void setSource(String s) { this.source = s == null ? "" : s.trim(); }
    public int interval() { return interval; }
    public void setInterval(int s) { this.interval = Math.max(1, Math.min(3600, s)); }
    public int points() { return points; }
    public void setPoints(int n) { this.points = Math.max(2, Math.min(500, n)); }
    public Style style() { return style; }
    public void setStyle(Style s) { this.style = s == null ? Style.AREA : s; }
    public int lineColor() { return lineColor; }
    public void setLineColor(int c) { this.lineColor = c; }
    public int fillColor() { return fillColor; }
    public void setFillColor(int c) { this.fillColor = c; }
    public boolean autoMin() { return autoMin; }
    public boolean autoMax() { return autoMax; }
    public void setAutoMin(boolean b) { this.autoMin = b; }
    public void setAutoMax(boolean b) { this.autoMax = b; }
    public double min() { return min; }
    public double max() { return max; }
    public void setMin(double v) { this.min = v; }
    public void setMax(double v) { this.max = v; }
    public boolean labels() { return labels; }
    public void setLabels(boolean b) { this.labels = b; }
    public float scale() { return scale; }
    public void setScale(float s) { this.scale = Math.max(0.01f, s); }
    public int layer() { return layer; }
    public void setLayer(int l) { this.layer = l; }

    /** The placeholder token (without braces) the server samples. */
    public String sourceToken() {
        String s = source.trim();
        if (s.startsWith("{") && s.endsWith("}") && s.length() > 2) s = s.substring(1, s.length() - 1);
        return s;
    }

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
    @Override public void setSize(double w, double h) { this.w = Math.max(8, w); this.h = Math.max(8, h); }
    @Override public float rotation() { return rotationDeg; }
    @Override public void setRotation(float deg) { this.rotationDeg = deg; }
    @Override public boolean hit(double px, double py) { return px >= x && py >= y && px <= x + w * scale && py <= y + h * scale; }
    @Override public String kind() { return "chart"; }

    @Override public void saveTo(CompoundTag out) {
        out.putString("kind", "chart");
        out.putString("id", id);
        out.putDouble("x", x); out.putDouble("y", y);
        out.putDouble("w", w); out.putDouble("h", h);
        out.putString("src", source);
        out.putInt("interval", interval);
        out.putInt("points", points);
        out.putString("style", style.name().toLowerCase(Locale.ROOT));
        out.putInt("line", lineColor);
        out.putInt("fill", fillColor);
        if (!autoMin) out.putDouble("min", min);
        if (!autoMax) out.putDouble("max", max);
        if (!labels) out.putBoolean("noLabels", true);
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
        w = in.contains("w") ? in.getDouble("w") : 96;
        h = in.contains("h") ? in.getDouble("h") : 40;
        source = in.getString("src");
        interval = in.contains("interval") ? Math.max(1, in.getInt("interval")) : 5;
        points = in.contains("points") ? Math.max(2, in.getInt("points")) : 60;
        try { style = Style.valueOf(in.getString("style").toUpperCase(Locale.ROOT)); } catch (Exception e) { style = Style.AREA; }
        lineColor = in.contains("line") ? in.getInt("line") : 0xFF2F81F7;
        fillColor = in.contains("fill") ? in.getInt("fill") : 0x552F81F7;
        autoMin = !in.contains("min");
        autoMax = !in.contains("max");
        min = in.getDouble("min");
        max = in.contains("max") ? in.getDouble("max") : 20;
        labels = !in.getBoolean("noLabels");
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
