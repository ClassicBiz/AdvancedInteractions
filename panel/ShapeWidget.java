package com.luminacity.advancedinteractions.panel;
import com.mojang.brigadier.arguments.DoubleArgumentType;

import net.minecraft.nbt.CompoundTag;



/**
 * Render-only widget used as building blocks for signs.
 *
 * Shapes are now fully pixel-based, consistent with TextWidget/ButtonWidget:
 *   - position: x/y (panel pixels)
 *   - size: w/h (panel pixels)
 *   - scale: render-only multiplier (does not change stored w/h)
 *
 * Back-compat:
 * Older saved shapes used grid keys (row/col/rows/cols). On load, we convert those
 * to pixels using PanelUnits and then continue saving as x/y/w/h.
 */
public class ShapeWidget implements PanelWidget {

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

    public enum Type {
        // Basic
        RECT, RRECT, SQUARE,
        LINE,

        // Pointing
        TRIANGLE, RTRIANGLE, ARROW, CHEVRON,

        // Curves
        OVAL, CIRCLE,

        // Polygons
        PENTAGON, HEXAGON,

        // Decorative
        STAR,

        // Signage (US road shields)
        ROUTE_SHIELD,
        INTERSTATE_SHIELD;

        /** Lenient parser for commands (accepts common aliases). */
        public static Type from(String s) {
            if (s == null) return RECT;
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (t) {
                case "rect", "rectangle", "panel", "box" -> RECT;
                case "rrect", "roundrect", "roundedrect", "rounded_rect", "rounded" -> RRECT;
                case "square", "sq" -> SQUARE;

                case "line", "divider" -> LINE;

                case "tri", "triangle" -> TRIANGLE;
                case "rtri", "roundtri", "roundedtri", "roundedtriangle", "rounded_triangle" -> RTRIANGLE;
                case "arrow" -> ARROW;
                case "chevron", "chev" -> CHEVRON;

                case "oval", "ellipse" -> OVAL;
                case "circle", "circ" -> CIRCLE;

                case "pentagon", "penta" -> PENTAGON;
                case "hexagon", "hexa", "hex" -> HEXAGON;

                case "star", "stars" -> STAR;

                // US road shields
                case "route", "usroute", "us_route", "route_shield", "us_shield", "highway_shield", "highway" -> ROUTE_SHIELD;
                case "interstate", "interstate_shield", "i_shield", "i" -> INTERSTATE_SHIELD;

                default -> RECT;
            };
        }
    }

    public enum Dir {
        UP, DOWN, LEFT, RIGHT;

        /** Lenient parser for commands (accepts n/s/e/w + arrows). */
        public static Dir from(String s) {
            if (s == null) return RIGHT;
            String d = s.trim().toLowerCase(java.util.Locale.ROOT);
            return switch (d) {
                case "up", "u", "north", "n" -> UP;
                case "down", "d", "south", "s" -> DOWN;
                case "left", "l", "west", "w" -> LEFT;
                case "right", "r", "east", "e" -> RIGHT;
                default -> RIGHT;
            };
        }
    }

    private String id;
    private Type type = Type.RECT;
    private Dir dir = Dir.RIGHT;

    /**
     * Layer / z-index for shapes ONLY.
     * Higher numbers render on top of lower numbers.
     * Text + buttons always render above shapes regardless of layer.
     */
    private int layer = 0;

    // Pixel placement (panel coordinate space)
    private double x = 0;
    private double y = 0;
    private double w = PanelUnits.COL_PX;
    private double h = PanelUnits.ROW_PX;

    /**
     * Optional corner radius in pixels for rounded shapes.
     * If 0, renderers may pick a sensible default.
     */
    private double cornerRadius = 0;

    // Colors are ARGB
    private int fill = 0xFFFFFFFF;
    private int border = 0x00000000;
    private double borderWidth = 0.0; // panel pixels; can be fractional

    // Render-time multiplier only
    private float scale = 1.0f;

    public ShapeWidget() {}

    public ShapeWidget(String id, Type type) {
        this.id = id;
        this.type = type == null ? Type.RECT : type;
    }

    public int layer() { return layer; }
    public void setLayer(int layer) { this.layer = layer; }

    public Type type() { return type; }
    public void setType(Type t) { this.type = t == null ? Type.RECT : t; }

    public Dir dir() { return dir; }
    public void setDir(Dir d) { this.dir = d == null ? Dir.RIGHT : d; }

    public int fill() { return fill; }
    public void setFill(int argb) { this.fill = argb; }

    public int border() { return border; }
    public void setBorder(int argb) { this.border = argb; }

    /** Convenience overload used by command layer: sets border color + border width. */

    public void setBorder(int argb, double width) {
        this.border = argb;
        this.borderWidth = Math.max(0.0, width);
    }

    /** Clears border completely (color + width). */
    public void clearBorder() {
        this.border = 0x00000000;
        this.borderWidth = 0.0;
    }

    public double borderWidth() { return borderWidth; }
    public void setBorderWidth(double w) { this.borderWidth = Math.max(0.0, w); }

    public float scale() { return scale; }
    public void setScale(float s) { this.scale = Math.max(0.01f, s); }

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

    public double cornerRadius() { return cornerRadius; }
    public void setCornerRadius(double r) { this.cornerRadius = Math.max(0, r); }

    @Override public float rotation() { return rotationDeg; }
    @Override public void setRotation(float deg) { this.rotationDeg = deg; }

    @Override public void setPos(double x, double y) {
        this.x = x;
        this.y = y;
    }

    @Override public void setSize(double w, double h) {
        this.w = Math.max(0, w);
        this.h = Math.max(0, h);
    }

    @Override public boolean hit(double px, double py) {
        // Render-only by default.
        return false;
    }

    @Override public String kind() { return "shape"; }

    @Override public void saveTo(CompoundTag out) {
        out.putString("kind", "shape");
        out.putString("id", id);
        out.putString("type", type.name());
        out.putString("dir", dir.name());

        if (layer != 0) out.putInt("layer", layer);

        // pixel placement
        out.putDouble("x", x);
        out.putDouble("y", y);
        out.putDouble("w", w);
        out.putDouble("h", h);

        out.putInt("fill", fill);
        if (borderWidth > 0.0) {
            out.putInt("border", border);
            out.putDouble("borderWidth", borderWidth);
        }
        if (cornerRadius > 0) out.putDouble("cornerRadius", cornerRadius);
        if (scale != 1.0f) out.putFloat("scale", scale);
        if (rotationDeg != 0.0f) out.putFloat("rot", rotationDeg);

        if (visPerm != null && !visPerm.isBlank()) out.putString("visPerm", visPerm);
        if (visOp >= 0) out.putInt("visOp", visOp);
        if (condition != null && !condition.isBlank()) out.putString("cond", condition);
        anim.saveTo(out);
        if (!click.isDefault()) { CompoundTag c = new CompoundTag(); click.saveTo(c); out.put("click", c); }
        if (startHidden) out.putBoolean("startHidden", true);
    }

    @Override public void loadFrom(CompoundTag in) {
        id = in.getString("id");
        try { type = Type.valueOf(in.getString("type")); } catch (Exception ignored) {}
        try { dir = Dir.valueOf(in.getString("dir")); } catch (Exception ignored) {}

        layer = in.contains("layer") ? in.getInt("layer") : 0;

        // New pixel-based format
        if (in.contains("x") || in.contains("y") || in.contains("w") || in.contains("h")) {
            x = in.contains("x") ? in.getDouble("x") : 0;
            y = in.contains("y") ? in.getDouble("y") : 0;
            w = in.contains("w") ? in.getDouble("w") : PanelUnits.COL_PX;
            h = in.contains("h") ? in.getDouble("h") : PanelUnits.ROW_PX;
        } else {
            // Legacy grid-based format (row/col/rows/cols)
            int row = in.contains("row") ? in.getInt("row") : 0;
            int col = in.contains("col") ? in.getInt("col") : 0;
            int rows = in.contains("rows") ? in.getInt("rows") : 1;
            int cols = in.contains("cols") ? in.getInt("cols") : 1;

            x = col * PanelUnits.COL_PX;
            y = row * PanelUnits.ROW_PX;
            w = Math.max(0, cols * PanelUnits.COL_PX);
            h = Math.max(0, rows * PanelUnits.ROW_PX);
        }

        fill = in.contains("fill") ? in.getInt("fill") : 0xFFFFFFFF;
        border = in.contains("border") ? in.getInt("border") : 0x00000000;
        if (in.contains("borderWidth")) {
            var t = in.get("borderWidth");
            borderWidth = (t instanceof net.minecraft.nbt.IntTag)
                    ? in.getInt("borderWidth")
                    : in.getDouble("borderWidth");
        } else {
            borderWidth = 0.0;
        }
        cornerRadius = in.contains("cornerRadius") ? in.getDouble("cornerRadius") : 0;
        scale = in.contains("scale") ? in.getFloat("scale") : 1.0f;
        rotationDeg = in.contains("rot") ? in.getFloat("rot") : 0.0f;

        visPerm = in.contains("visPerm") ? in.getString("visPerm") : "";
        visOp = in.contains("visOp") ? in.getInt("visOp") : -1;
        condition = in.contains("cond") ? in.getString("cond") : "";
        anim = WidgetAnim.load(in);
        click = ClickSettings.load(in.contains("click") ? in.getCompound("click") : null);
        startHidden = in.getBoolean("startHidden");
    }
}
