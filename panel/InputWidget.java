package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A field the player fills in: number, slider, text, toggle or dropdown. The value is kept per player
 * on the server and read with {@code {input.NAME}} (e.g. a quantity for a buy button). Min/max may be
 * placeholders ({@code {econ.balance}}); the server re-checks every value it receives.
 *
 * <p>The click settings' action (optional) runs after the value changes.</p>
 */
public class InputWidget implements PanelWidget {
    public enum Type { NUMBER, SLIDER, TEXT, TOGGLE, DROPDOWN }

    private String id;
    private double x, y, w = 80, h = 14;
    private float scale = 1f, rotationDeg = 0f;
    private Type type = Type.NUMBER;
    /** Variable name ({input.NAME}); empty = the widget id. */
    private String name = "";
    private String label = "";
    private String min = "0", max = "64";
    private double step = 1;
    /** Dropdown choices, comma separated. */
    private String options = "";
    private String defaultValue = "";
    /** Max characters for text fields. */
    private int maxLength = 32;
    private int fill = 0xCC1E1E28, accent = 0xFF3A7BD5, textColor = 0xFFFFFFFF;

    private String visPerm = "";
    private int visOp = -1;
    private String condition = "";
    private WidgetAnim anim = new WidgetAnim();
    private ClickSettings click = new ClickSettings();
    private boolean startHidden;

    public InputWidget() {}
    public InputWidget(String id, double x, double y, Type type) { this.id = id; this.x = x; this.y = y; this.type = type; }

    public Type type() { return type; }
    public void setType(Type t) { this.type = t == null ? Type.NUMBER : t; }
    /** The variable name used in {input.NAME}. */
    public String name() { return name == null || name.isBlank() ? id : name; }
    public String rawName() { return name; }
    public void setName(String n) { this.name = n == null ? "" : n.trim().toLowerCase(Locale.ROOT); }
    public String label() { return label; }
    public void setLabel(String l) { this.label = l == null ? "" : l; }
    public String min() { return min; }
    public void setMin(String v) { this.min = v == null ? "" : v.trim(); }
    public String max() { return max; }
    public void setMax(String v) { this.max = v == null ? "" : v.trim(); }
    public double step() { return step; }
    public void setStep(double s) { this.step = s <= 0 ? 1 : s; }
    public String options() { return options; }
    public void setOptions(String o) { this.options = o == null ? "" : o; }
    public List<String> optionList() {
        List<String> out = new ArrayList<>();
        for (String s : options.split(",")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }
    public String defaultValue() { return defaultValue; }
    public void setDefaultValue(String v) { this.defaultValue = v == null ? "" : v; }
    public int maxLength() { return maxLength; }
    public void setMaxLength(int n) { this.maxLength = Math.max(1, Math.min(256, n)); }
    public float scale() { return scale; }
    public void setScale(float s) { this.scale = s; }
    public int fill() { return fill; }
    public void setFill(int c) { this.fill = c; }
    public int accent() { return accent; }
    public void setAccent(int c) { this.accent = c; }
    public int textColor() { return textColor; }
    public void setTextColor(int c) { this.textColor = c; }

    /** The value before the player changes it. */
    public String initialValue() {
        if (!defaultValue.isBlank()) return defaultValue;
        return switch (type) {
            case TOGGLE -> "false";
            case TEXT -> "";
            case DROPDOWN -> optionList().isEmpty() ? "" : optionList().get(0);
            default -> min.matches("-?[0-9.]+") ? min : "0";
        };
    }

    @Override public ClickSettings interaction() { return click; }
    @Override public boolean startHidden() { return startHidden; }
    @Override public void setStartHidden(boolean b) { this.startHidden = b; }
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
    @Override public void setSize(double w, double h) { this.w = Math.max(4, w); this.h = Math.max(4, h); }
    @Override public float rotation() { return rotationDeg; }
    @Override public void setRotation(float deg) { this.rotationDeg = deg; }
    @Override public boolean hit(double px, double py) { return px >= x && py >= y && px <= x + w * scale && py <= y + h * scale; }
    @Override public String kind() { return "input"; }

    @Override public void saveTo(CompoundTag out) {
        out.putString("kind", "input");
        out.putString("id", id);
        out.putDouble("x", x); out.putDouble("y", y);
        out.putDouble("w", w); out.putDouble("h", h);
        if (scale != 1f) out.putFloat("scale", scale);
        if (rotationDeg != 0f) out.putFloat("rot", rotationDeg);
        out.putString("type", type.name().toLowerCase(Locale.ROOT));
        if (!name.isBlank()) out.putString("name", name);
        if (!label.isBlank()) out.putString("label", label);
        out.putString("min", min);
        out.putString("max", max);
        if (step != 1) out.putDouble("step", step);
        if (!options.isBlank()) out.putString("options", options);
        if (!defaultValue.isBlank()) out.putString("default", defaultValue);
        if (maxLength != 32) out.putInt("maxLen", maxLength);
        out.putInt("fill", fill);
        out.putInt("accent", accent);
        out.putInt("textColor", textColor);
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
        w = in.contains("w") ? in.getDouble("w") : 80;
        h = in.contains("h") ? in.getDouble("h") : 14;
        scale = in.contains("scale") ? in.getFloat("scale") : 1f;
        rotationDeg = in.getFloat("rot");
        try { type = Type.valueOf(in.getString("type").toUpperCase(Locale.ROOT)); } catch (Exception e) { type = Type.NUMBER; }
        name = in.getString("name");
        label = in.getString("label");
        min = in.contains("min") ? in.getString("min") : "0";
        max = in.contains("max") ? in.getString("max") : "64";
        step = in.contains("step") ? in.getDouble("step") : 1;
        options = in.getString("options");
        defaultValue = in.getString("default");
        maxLength = in.contains("maxLen") ? in.getInt("maxLen") : 32;
        if (in.contains("fill")) fill = in.getInt("fill");
        if (in.contains("accent")) accent = in.getInt("accent");
        if (in.contains("textColor")) textColor = in.getInt("textColor");
        visPerm = in.getString("visPerm");
        visOp = in.contains("visOp") ? in.getInt("visOp") : -1;
        condition = in.getString("cond");
        anim = WidgetAnim.load(in);
        click = ClickSettings.load(in.contains("click") ? in.getCompound("click") : null);
        startHidden = in.getBoolean("startHidden");
    }
}
