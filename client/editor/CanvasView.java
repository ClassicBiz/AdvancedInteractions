package com.luminacity.advancedinteractions.client.editor;

import com.luminacity.advancedinteractions.client.editor.ui.Theme;
import com.luminacity.advancedinteractions.client.editor.ui.Ui;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.luminacity.advancedinteractions.panel.ButtonWidget;
import com.luminacity.advancedinteractions.panel.PanelRenderer;
import com.luminacity.advancedinteractions.panel.PanelStyle;
import com.luminacity.advancedinteractions.panel.PanelUnits;
import com.luminacity.advancedinteractions.panel.PanelWidget;
import com.luminacity.advancedinteractions.panel.ShapeWidget;
import com.luminacity.advancedinteractions.panel.TextWidget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WYSIWYG panel canvas: draws the panel with the real renderer and lets you select, drag, resize,
 * nudge, copy and paste widgets. Zoom with the wheel, pan with right/middle drag.
 */
public class CanvasView extends Ui {
    private static final int HANDLE = 6;
    /** Copied widgets (shared across holograms in this session). */
    private static List<PanelWidget> clipboard = new ArrayList<>();

    private final EditorSession s;
    public float zoom = 2f;
    private double panX, panY;
    private boolean fitted;
    public int grid = 4;
    public boolean snap = true;
    public boolean showGrid = true;
    /** Show {placeholders} as typed (true) or resolved for you (false). */
    public boolean raw = true;

    private enum Mode { NONE, MOVE, RESIZE, PANEL_RESIZE, PAN }
    private Mode mode = Mode.NONE;
    private double startMx, startMy;
    private final Map<String, double[]> startPos = new HashMap<>();
    private double[] startSize;
    private int gesture;

    public CanvasView(EditorSession s) { this.s = s; }

    // ---------------------------------------------------------------- geometry

    private int pad() { return s.def.panelStyle != null ? s.def.panelStyle.padding : 6; }
    private int panelW() { return Math.max(s.def.cols * PanelUnits.COL_PX, 64) + pad() * 2; }
    private int panelH() { return Math.max(s.def.rows * PanelUnits.ROW_PX, 16) + pad() * 2; }
    private double ox() { return x + panX; }
    private double oy() { return y + panY; }
    private double toPanelX(double mx) { return (mx - ox()) / zoom; }
    private double toPanelY(double my) { return (my - oy()) / zoom; }

    public void fit() {
        float zx = (w - 24) / (float) panelW(), zy = (h - 40) / (float) panelH();
        zoom = Math.max(0.25f, Math.min(8f, Math.min(zx, zy)));
        panX = (w - panelW() * zoom) / 2.0;
        panY = (h - panelH() * zoom) / 2.0;
        fitted = true;
    }

    /** Box of a widget in panel pixels, measured as drawn. */
    private float[] box(PanelWidget w) {
        PanelRenderer.rawPlaceholders = raw;
        try {
            return PanelRenderer.widgetBox(s.def, w, Minecraft.getInstance().level, Minecraft.getInstance().player);
        } finally {
            PanelRenderer.rawPlaceholders = false;
        }
    }

    /** Widgets in draw order (shapes by layer first, then text and buttons). */
    private List<PanelWidget> drawOrder() {
        List<PanelWidget> list = s.list();
        List<PanelWidget> shapes = new ArrayList<>();
        List<PanelWidget> rest = new ArrayList<>();
        for (PanelWidget w : list) (layerOf(w) != null ? shapes : rest).add(w);
        shapes.sort(Comparator.comparingInt(this::layerOrZero));
        shapes.addAll(rest);
        return shapes;
    }

    /** Layer of widgets drawn in the background pass (shapes, images, charts); null for text and buttons. */
    private Integer layerOf(PanelWidget w) {
        if (w instanceof ShapeWidget sw) return sw.layer();
        if (w instanceof com.luminacity.advancedinteractions.panel.ImageWidget iw) return iw.layer();
        if (w instanceof com.luminacity.advancedinteractions.panel.ChartWidget cw) return cw.layer();
        return null;
    }

    private int layerOrZero(PanelWidget w) { Integer l = layerOf(w); return l == null ? 0 : l; }

    private PanelWidget pick(double mx, double my) {
        double px = toPanelX(mx), py = toPanelY(my);
        List<PanelWidget> order = drawOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            float[] b = box(order.get(i));
            if (px >= b[0] && py >= b[1] && px <= b[0] + b[2] && py <= b[1] + b[3]) return order.get(i);
        }
        return null;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(PoseStack p, int mx, int my, float pt) {
        if (!fitted && w > 0) fit();
        rect(p, x, y, w, h, 0xFF0B0E12);
        enableScissor(x, y, x + w, y + h);

        // Transparent panel areas over a checkerboard, like an image editor.
        int sx = (int) ox(), sy = (int) oy(), sw = Math.round(panelW() * zoom), sh = Math.round(panelH() * zoom);
        checker(p, sx, sy, sw, sh, Math.max(4, Math.round(8 * zoom)));
        rect(p, sx, sy, sw, sh, 0x99222222);

        var mc = Minecraft.getInstance();
        var buffers = mc.renderBuffers().bufferSource();
        p.pushPose();
        p.translate(ox(), oy(), 50);
        p.scale(zoom, zoom, 1);
        PanelRenderer.rawPlaceholders = raw;
        try {
            if (s.target.kind() == EditTarget.Kind.VIEW) {
                // The page this view overlays, dimmed, for context.
                PanelRenderer.renderPanel(canvasDef(s.target.underlay(s.def), true), p, buffers, mc.level, mc.player);
                buffers.endBatch();
                p.translate(0, 0, 1);
                drawDim(p);
                PanelRenderer.renderPanel(canvasDef(s.list(), false), p, buffers, mc.level, mc.player);
            } else {
                PanelRenderer.renderPanel(canvasDef(s.list(), true), p, buffers, mc.level, mc.player);
            }
            buffers.endBatch();
        } catch (Exception e) {
            // A half-typed value must not take the editor down; draw what we can.
        } finally {
            PanelRenderer.rawPlaceholders = false;
        }
        p.popPose();
        RenderSystem.enableDepthTest();

        p.pushPose();
        p.translate(0, 0, 200);
        if (showGrid && grid * zoom >= 5) {
            int gc = 0x18FFFFFF;
            for (int gx = 0; gx <= panelW(); gx += grid) rect(p, sx + Math.round(gx * zoom), sy, 1, sh, gc);
            for (int gy = 0; gy <= panelH(); gy += grid) rect(p, sx, sy + Math.round(gy * zoom), sw, 1, gc);
        }
        outline(p, sx - 1, sy - 1, sw + 2, sh + 2, Theme.BORDER);

        PanelWidget hover = mode == Mode.NONE && contains(mx, my) ? pick(mx, my) : null;
        for (PanelWidget w : s.list()) {
            boolean sel = s.selection.contains(w.id());
            if (!sel && w != hover) continue;
            float[] b = box(w);
            int bx = (int) Math.floor(ox() + b[0] * zoom), by = (int) Math.floor(oy() + b[1] * zoom);
            int bw = Math.max(2, Math.round(b[2] * zoom)), bh = Math.max(2, Math.round(b[3] * zoom));
            outline(p, bx - 1, by - 1, bw + 2, bh + 2, sel ? Theme.BORDER_FOCUS : 0x88FFFFFF);
            if (sel && w == s.primary()) {
                rect(p, bx + bw - HANDLE / 2, by + bh - HANDLE / 2, HANDLE, HANDLE, Theme.BORDER_FOCUS);
                String tag = w.id();
                rect(p, bx, by - 11, font().width(tag) + 4, 10, Theme.ACCENT);
                text(p, tag, bx + 2, by - 10, 0xFFFFFFFF);
            }
        }
        // Panel resize handle (grid size).
        rect(p, sx + sw - 3, sy + sh - 3, 7, 7, mode == Mode.PANEL_RESIZE ? Theme.ACCENT : Theme.MUTED);

        String info = Math.round(zoom * 100) + "%  " + s.def.cols + "x" + s.def.rows + " cells  "
                + (snap ? "snap " + grid + "px" : "free") + (raw ? "" : "  live values");
        text(p, info, x + 4, y + h - 11, Theme.MUTED);
        p.popPose();
        disableScissor();
    }

    private HologramDef canvasDef(List<PanelWidget> widgets, boolean withBackground) {
        HologramDef d = new HologramDef();
        d.id = null; // no hover state or click rects: those belong to the in-world panel
        d.alias = s.def.alias;
        d.rows = s.def.rows;
        d.cols = s.def.cols;
        d.meta.putAll(s.def.meta);
        d.binding = s.def.binding;
        PanelStyle st = new PanelStyle();
        PanelStyle src = s.def.panelStyle == null ? new PanelStyle() : s.def.panelStyle;
        st.bg = withBackground ? src.bg : 0;
        st.padding = src.padding;
        st.radius = src.radius;
        st.uiScale = 1f; // the canvas zoom replaces UI scale
        st.brightness = src.brightness;
        d.panelStyle = st;
        d.widgets.addAll(widgets);
        return d;
    }

    private void drawDim(PoseStack p) {
        rect(p, 0, 0, panelW(), panelH(), 0x99000000);
    }

    // ---------------------------------------------------------------- input

    private boolean onHandle(double mx, double my, PanelWidget w) {
        if (w == null) return false;
        float[] b = box(w);
        double hx = ox() + (b[0] + b[2]) * zoom, hy = oy() + (b[1] + b[3]) * zoom;
        return Math.abs(mx - hx) <= HANDLE && Math.abs(my - hy) <= HANDLE;
    }

    private boolean onPanelHandle(double mx, double my) {
        double hx = ox() + panelW() * zoom, hy = oy() + panelH() * zoom;
        return Math.abs(mx - hx) <= 6 && Math.abs(my - hy) <= 6;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!contains(mx, my)) return false;
        startMx = mx;
        startMy = my;
        gesture++;
        if (button == 1 || button == 2) { mode = Mode.PAN; return true; }
        if (button != 0) return false;

        if (onPanelHandle(mx, my)) {
            mode = Mode.PANEL_RESIZE;
            startSize = new double[] { s.def.cols, s.def.rows };
            return true;
        }
        PanelWidget primary = s.primary();
        if (onHandle(mx, my, primary)) {
            mode = Mode.RESIZE;
            float[] b = box(primary);
            double sc = primary instanceof ButtonWidget bw ? bw.scale() : primary instanceof ShapeWidget sw ? sw.scale()
                    : primary instanceof com.luminacity.advancedinteractions.panel.ImageWidget iw ? iw.scale()
                    : primary instanceof com.luminacity.advancedinteractions.panel.ChartWidget cw ? cw.scale()
                    : primary instanceof com.luminacity.advancedinteractions.panel.InputWidget in ? in.scale() : 1;
            double tScale = primary instanceof TextWidget tw ? tw.scale() : 1;
            startSize = new double[] { b[2] / sc, b[3] / sc, tScale, b[2] };
            return true;
        }
        PanelWidget hit = pick(mx, my);
        if (hit == null) {
            if (!Screen.hasShiftDown()) s.select(null, false);
            mode = Mode.PAN;
            return true;
        }
        if (Screen.hasShiftDown()) s.select(hit.id(), true);
        else if (!s.selection.contains(hit.id())) s.select(hit.id(), false);
        else { s.selection.remove(hit.id()); s.selection.add(hit.id()); s.structureVersion++; }
        startPos.clear();
        for (PanelWidget w : s.selected()) startPos.put(w.id(), new double[] { w.x(), w.y() });
        mode = Mode.MOVE;
        return true;
    }

    private double snapV(double v) {
        if (!snap || Screen.hasAltDown()) return Math.round(v * 2) / 2.0;
        return Math.round(v / grid) * grid;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (mode == Mode.NONE) return false;
        double dpx = (mx - startMx) / zoom, dpy = (my - startMy) / zoom;
        switch (mode) {
            case PAN -> { panX += dx; panY += dy; }
            case MOVE -> s.edit("move" + gesture, () -> {
                for (PanelWidget w : s.selected()) {
                    double[] st = startPos.get(w.id());
                    if (st != null) w.setPos(snapV(st[0] + dpx), snapV(st[1] + dpy));
                }
            });
            case RESIZE -> {
                PanelWidget w = s.primary();
                if (w == null) break;
                s.edit("resize" + gesture, () -> resize(w, dpx, dpy));
            }
            case PANEL_RESIZE -> s.edit("panel" + gesture, () -> {
                s.def.cols = (int) Math.max(4, Math.round(startSize[0] + dpx / PanelUnits.COL_PX));
                s.def.rows = (int) Math.max(2, Math.round(startSize[1] + dpy / PanelUnits.ROW_PX));
            });
            default -> {}
        }
        return true;
    }

    private void resize(PanelWidget w, double dpx, double dpy) {
        if (w instanceof TextWidget tw) {
            double base = Math.max(1, startSize[3]);
            float sc = (float) Math.max(0.1, startSize[2] * (base + dpx) / base);
            tw.setScale(Math.round(sc * 20) / 20f);
        } else if (w instanceof ButtonWidget bw) {
            double sc = Math.max(0.01, bw.scale());
            bw.setSize(Math.max(4, snapV(startSize[0] + dpx / sc)), Math.max(4, snapV(startSize[1] + dpy / sc)));
        } else if (w instanceof ShapeWidget sw) {
            double sc = Math.max(0.01, sw.scale());
            sw.setSize(Math.max(1, snapV(startSize[0] + dpx / sc)), Math.max(1, snapV(startSize[1] + dpy / sc)));
        } else if (w instanceof com.luminacity.advancedinteractions.panel.ImageWidget iw) {
            double sc = Math.max(0.01, iw.scale());
            double nw = Math.max(1, snapV(startSize[0] + dpx / sc));
            // Shift keeps the image's proportions.
            double nh = Screen.hasShiftDown() ? nw * startSize[1] / Math.max(1, startSize[0]) : Math.max(1, snapV(startSize[1] + dpy / sc));
            iw.setSize(nw, nh);
        } else if (w instanceof com.luminacity.advancedinteractions.panel.ChartWidget cw) {
            double sc = Math.max(0.01, cw.scale());
            cw.setSize(Math.max(8, snapV(startSize[0] + dpx / sc)), Math.max(8, snapV(startSize[1] + dpy / sc)));
        } else if (w instanceof com.luminacity.advancedinteractions.panel.InputWidget in) {
            double sc = Math.max(0.01, in.scale());
            in.setSize(Math.max(12, snapV(startSize[0] + dpx / sc)), Math.max(8, snapV(startSize[1] + dpy / sc)));
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean was = mode != Mode.NONE;
        if (mode == Mode.MOVE || mode == Mode.RESIZE || mode == Mode.PANEL_RESIZE) s.structureVersion++;
        mode = Mode.NONE;
        return was;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!contains(mx, my)) return false;
        double px = toPanelX(mx), py = toPanelY(my);
        zoom = (float) Math.max(0.25, Math.min(10, zoom * (delta > 0 ? 1.15 : 1 / 1.15)));
        panX = mx - x - px * zoom;
        panY = my - y - py * zoom;
        return true;
    }

    /** Canvas shortcuts; the screen calls this when no text field has focus. */
    public boolean keyPressed(int key, int mods) {
        boolean ctrl = Screen.hasControlDown();
        double step = Screen.hasShiftDown() ? 10 : (Screen.hasAltDown() || !snap ? 1 : grid);
        switch (key) {
            case GLFW.GLFW_KEY_LEFT -> { nudge(-step, 0); return true; }
            case GLFW.GLFW_KEY_RIGHT -> { nudge(step, 0); return true; }
            case GLFW.GLFW_KEY_UP -> { nudge(0, -step); return true; }
            case GLFW.GLFW_KEY_DOWN -> { nudge(0, step); return true; }
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> { s.deleteSelected(); return true; }
            default -> {}
        }
        if (!ctrl) return false;
        switch (key) {
            case GLFW.GLFW_KEY_C -> { copy(); return true; }
            case GLFW.GLFW_KEY_X -> { copy(); s.deleteSelected(); return true; }
            case GLFW.GLFW_KEY_V -> { s.paste(clipboard, grid * 2, grid * 2); return true; }
            case GLFW.GLFW_KEY_D -> { duplicate(); return true; }
            case GLFW.GLFW_KEY_A -> {
                s.selection.clear();
                for (PanelWidget w : s.list()) s.selection.add(w.id());
                s.structureVersion++;
                return true;
            }
            default -> { return false; }
        }
    }

    private void nudge(double dx, double dy) {
        if (s.selection.isEmpty()) return;
        s.edit("nudge", () -> { for (PanelWidget w : s.selected()) w.setPos(w.x() + dx, w.y() + dy); });
    }

    public void copy() { clipboard = HologramDef.deepCopyWidgets(s.selected()); }

    public boolean hasClipboard() { return !clipboard.isEmpty(); }

    public void pasteClipboard() { s.paste(clipboard, grid * 2, grid * 2); }

    public void duplicate() {
        List<PanelWidget> sel = s.selected();
        if (!sel.isEmpty()) s.paste(sel, grid * 2, grid * 2);
    }

    /** Where to drop a new widget: the middle of what's visible, in panel pixels (padding excluded). */
    public double[] dropPoint() {
        double px = toPanelX(x + w / 2.0) - pad(), py = toPanelY(y + h / 2.0) - pad();
        px = Math.max(0, Math.min(panelW() - pad() * 2 - 16, px));
        py = Math.max(0, Math.min(panelH() - pad() * 2 - 8, py));
        return new double[] { Math.round(px / grid) * grid, Math.round(py / grid) * grid };
    }
}
