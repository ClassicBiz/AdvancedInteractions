package com.luminacity.advancedinteractions.panel;

import com.luminacity.advancedinteractions.api.Placeholders;
import com.luminacity.advancedinteractions.client.PanelClickCache;
import com.luminacity.advancedinteractions.hologram.HologramDef;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.brigadier.arguments.DoubleArgumentType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraftforge.registries.ForgeRegistries;

public class PanelRenderer {

    // Cached client-side entities for inline rendering (avoids constantly constructing entities).
    private static final Map<String, Entity> ENTITY_INLINE_CACHE = new LinkedHashMap<>();
    private static Level ENTITY_INLINE_CACHE_LEVEL = null;

    private static void beginPanel2D(float a) {
        // Keep depth testing (so panels occlude behind blocks), but don't write depth.
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);

        // Always enable blending so alpha=0 is truly transparent (fixes 'opacity 0 turns black').
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // The position-colour shader multiplies by the global shader colour; never inherit a tint/alpha
        // left behind by vanilla (world border) or other mods' inline renders.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private static void endPanel2D() {
        // Restore common state
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static float clampF(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private static int clamp255(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    /** Opacity of the widget being drawn (animations); 1 = as configured. Render thread only. */
    private static float alphaMul = 1f;

    /** Scale an alpha by {@link #alphaMul}. Fonts treat alpha below 4 as opaque, so visible text stays >= 4. */
    private static int mulAlpha(int a) {
        if (alphaMul >= 1f || a == 0) return a;
        return Math.max(4, Math.round(a * alphaMul));
    }

    /** Multiply RGB by brightness, alpha by the current animation opacity. */
    private static int applyBrightnessToArgb(int argb, float brightness) {
        if (brightness == 1.0f && alphaMul >= 1f) return argb;
        int a = mulAlpha((argb >>> 24) & 0xFF);
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = (argb) & 0xFF;
        r = clamp255(Math.round(r * brightness));
        g = clamp255(Math.round(g * brightness));
        b = clamp255(Math.round(b * brightness));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Multiply 0xRRGGBB by brightness. */
    private static int applyBrightnessToRgb(int rgb, float brightness) {
        if (brightness == 1.0f) return rgb & 0xFFFFFF;
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = (rgb) & 0xFF;
        r = clamp255(Math.round(r * brightness));
        g = clamp255(Math.round(g * brightness));
        b = clamp255(Math.round(b * brightness));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Editor canvas: when true, text is drawn with its {placeholders} as written instead of resolved,
     * so editors see what they typed. Set only around an editor render call (render thread).
     */
    public static boolean rawPlaceholders = false;

    private static String resolveForDisplay(String text, Placeholders.PHContext ctx) {
        // & colour codes (&a, &l, &#RRGGBB) work everywhere, like § codes.
        return com.luminacity.advancedinteractions.util.TextFmt.mc(rawPlaceholders ? (text == null ? "" : text) : Placeholders.apply(text, ctx));
    }

    /**
     * The box a widget occupies in panel pixels (padding included, rotation ignored), measured exactly
     * as {@link #renderPanel} lays it out. Used by the editor for selection and dragging.
     *
     * @return {x, y, w, h}
     */
    public static float[] widgetBox(HologramDef def, PanelWidget w, net.minecraft.world.level.Level level,
                                    net.minecraft.world.entity.player.Player viewer) {
        int pad = def.panelStyle != null ? def.panelStyle.padding : 6;
        Font font = Minecraft.getInstance().font;
        int iconPx = Math.max(16, font.lineHeight + 1);
        Placeholders.PHContext ph = new Placeholders.PHContext(level, viewer, def);
        if (w instanceof TextWidget tw) {
            int textW = RichText.cached(resolveForDisplay(tw.text(), ph)).measureWidth(font, iconPx);
            float s = tw.scale();
            float sx = (float) tw.x() + pad;
            if ("center".equals(tw.align())) sx -= (textW * s) / 2f;
            else if ("right".equals(tw.align())) sx -= textW * s;
            return new float[] { sx, (float) tw.y() + pad + 2, Math.max(2, textW * s), Math.max(2, font.lineHeight * s) };
        }
        if (w instanceof ButtonWidget bw) {
            int textW = RichText.cached(resolveForDisplay(bw.label(), ph)).measureWidth(font, iconPx);
            ButtonStyle st = bw.style();
            float iconW = st.icon.isBlank() ? 0f : Math.max(2f, st.iconSize);
            float contentW = st.icon.isBlank() ? textW : st.iconPos == ButtonStyle.IconPos.ONLY ? iconW : textW + iconW + ICON_GAP;
            double bw0 = bw.w() > 0 ? bw.w() : contentW + 8;
            double bh0 = bw.h() > 0 ? bw.h() : Math.max(font.lineHeight, iconW) + 6;
            return new float[] { (float) (pad + bw.x()), (float) (pad + bw.y()), (float) (bw0 * bw.scale()), (float) (bh0 * bw.scale()) };
        }
        if (w instanceof ShapeWidget sw) {
            return new float[] { (float) sw.x() + pad, (float) sw.y() + pad, (float) (sw.w() * sw.scale()), (float) (sw.h() * sw.scale()) };
        }
        if (w instanceof ImageWidget iw) {
            return new float[] { (float) iw.x() + pad, (float) iw.y() + pad, (float) (iw.w() * iw.scale()), (float) (iw.h() * iw.scale()) };
        }
        if (w instanceof ChartWidget cw) {
            return new float[] { (float) cw.x() + pad, (float) cw.y() + pad, (float) (cw.w() * cw.scale()), (float) (cw.h() * cw.scale()) };
        }
        if (w instanceof InputWidget in) {
            return new float[] { (float) in.x() + pad, (float) in.y() + pad, (float) (in.w() * in.scale()), (float) (in.h() * in.scale()) };
        }
        return new float[] { (float) w.x() + pad, (float) w.y() + pad, 4, 4 };
    }

     public static void renderPanel(HologramDef def, PoseStack pose, MultiBufferSource buffers, net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player viewer) {
        Placeholders.PHContext phCtx = new Placeholders.PHContext(level, viewer, def);
        float brightness = def.panelStyle != null ? def.panelStyle.brightness : 1.0f;
        brightness = clampF(brightness, 0.0f, 2.0f);
        int pad = def.panelStyle != null ? def.panelStyle.padding : 6;
        int pxW = Math.max( (def.cols * PanelUnits.COL_PX), 64);
        int pxH = Math.max( (def.rows * PanelUnits.ROW_PX), 16);
        int totalW = pxW + pad*2;
        int totalH = pxH + pad*2;

        // Background at base Z layer
        pose.pushPose();
        int bgCol = def.panelStyle != null ? def.panelStyle.bg : 0xAA000000;
        PanelStyle ps = def.panelStyle;
        if (ps == null || (ps.cornerRadius <= 0 && ps.borderWidth <= 0)) {
            drawQuad(pose, totalW, totalH, applyBrightnessToArgb(bgCol, brightness));
        } else {
            // Rounded corners and/or a border: same outline helpers as styled buttons.
            ButtonStyle.Shape sh = ps.cornerRadius > 0 ? ButtonStyle.Shape.ROUNDED : ButtonStyle.Shape.SQUARE;
            float[] body = bodyOutline(sh, ps.cornerRadius, totalW, totalH, 0);
            int c = applyBrightnessToArgb(bgCol, brightness);
            fillOutline(pose, body, totalW, totalH, c, c);
            if (ps.borderWidth > 0) {
                fillRing(pose, body, bodyOutline(sh, ps.cornerRadius, totalW, totalH, ps.borderWidth),
                        applyBrightnessToArgb(ps.borderColor, brightness));
            }
        }
        pose.popPose();

        Font font = Minecraft.getInstance().font;

        // 1) background shapes first (sorted by shape layer / z-index)
        // Higher layer values draw last (on top of lower values).
        // Shapes, images and charts share this background pass, ordered by layer.
        class LayerEntry {
            final int index;
            final PanelWidget w;
            final int layer;
            LayerEntry(int index, PanelWidget w, int layer) { this.index = index; this.w = w; this.layer = layer; }
        }

        List<LayerEntry> shapes = new ArrayList<>();
        for (int i = 0; i < def.widgets.size(); i++) {
            PanelWidget w = def.widgets.get(i);
            if (w instanceof ShapeWidget sw) shapes.add(new LayerEntry(i, sw, sw.layer()));
            else if (w instanceof ImageWidget iw) shapes.add(new LayerEntry(i, iw, iw.layer()));
            else if (w instanceof ChartWidget cw) shapes.add(new LayerEntry(i, cw, cw.layer()));
        }
        shapes.sort(Comparator
                .comparingInt((LayerEntry e) -> e.layer)
                .thenComparingInt(e -> e.index));

        java.util.Set<String> disabledIds = disabledIds(def);
        List<PanelClickCache.ButtonRect> buttonRects = new ArrayList<>();

        for (LayerEntry e : shapes) {
                Fx fx = fx(def, e.w, disabledIds);
                WidgetAnim.Frame af = fx.apply(animFrame(def, e.w));
                float rot = e.w.rotation() + af.rotation();
                if (fx.tracked) buttonRects.add(layeredRect(e.w, af, rot, fx));
                if (af.alpha() < 0.02f) continue;
                alphaMul = af.alpha();
                float bright = brightness * af.bright();
                if (e.w instanceof ImageWidget iw) {
                    drawImageWidget(pose, iw, pad, af, bright, phCtx);
                    alphaMul = 1f;
                    continue;
                }
                if (e.w instanceof ChartWidget cw) {
                    drawChartWidget(pose, buffers, font, cw, pad, af, bright, phCtx, def.id == null);
                    alphaMul = 1f;
                    continue;
                }
                ShapeWidget sw = (ShapeWidget) e.w;
                float sx = (float) sw.x() + pad + af.dx();
                float sy = (float) sw.y() + pad + af.dy();
                int ww = (int) Math.round(sw.w() * sw.scale());
                int hh = (int) Math.round(sw.h() * sw.scale());

                pose.pushPose();
                pose.translate(sx, sy, 0);
                transformAbout(pose, ww, hh, rot, af.scale());

                // Shapes: render optional border first (same shape, then inset fill)
                renderShape(pose, ww, hh, sw, bright);
                RenderSystem.enableDepthTest();
                pose.popPose();
                alphaMul = 1f;
        }

        // 2) text/buttons above
        for (PanelWidget w : def.widgets) {
            if (!(w instanceof TextWidget) && !(w instanceof ButtonWidget) && !(w instanceof InputWidget)) continue;
            Fx fx = fx(def, w, disabledIds);
            WidgetAnim.Frame af = fx.apply(animFrame(def, w));
            // Invisible this frame (blink etc.): a clickable keeps its click area but isn't drawn.
            if (af.alpha() < 0.02f && !fx.tracked) continue;
            alphaMul = Math.min(1f, af.alpha());
            if (w instanceof TextWidget tw) {
                float sx = (float) tw.x() + pad + af.dx();
                float sy = (float) tw.y() + pad + 2 + af.dy();
                float scale = tw.scale();

                // Rich text: supports inline item/entity icons like :iron_ore: or :modid:item:
                int iconPx = Math.max(16, font.lineHeight + 1);
                String resolved = animText(def, tw, resolveForDisplay(tw.text(), phCtx));
                RichText rich = RichText.cached(resolved);

                int textW = rich.measureWidth(font, iconPx);
                int textH = font.lineHeight;

                // Alignment: x is the left edge, the centre, or the right edge of the text.
                String align = tw.align();
                if ("center".equals(align)) sx -= (textW * scale) / 2f;
                else if ("right".equals(align)) sx -= textW * scale;

                float rot = tw.rotation() + af.rotation();
                if (fx.tracked) {
                    buttonRects.add(rect(tw.id(), ClickSettings.primaryAction(tw), sx - pad, sy - pad,
                            Math.max(2, textW), textH, Math.max(2, textW), textH, scale, rot, af.scale(),
                            PanelClickCache.ButtonRect.RECT, 0, 0, fx));
                }
                if (af.alpha() < 0.02f) { alphaMul = 1f; continue; }

                pose.pushPose();
                pose.translate(sx, sy, 0);
                transformAbout(pose, textW, textH, rot, af.scale());
                pose.scale(scale, scale, 1.0f);
                RenderSystem.enableDepthTest();
                int textColor = tw.anim() == null ? tw.color() : tw.anim().color(tw.color());
                rich.render(pose, buffers, font, 0, 0, textColor, iconPx, brightness * af.bright());

                pose.popPose();
            } else if (w instanceof InputWidget in) {
                float s = in.scale();
                float iw = (float) in.w(), ih = (float) in.h();
                float rot = in.rotation() + af.rotation();
                buttonRects.add(rect(in.id(), "", in.x() + af.dx(), in.y() + af.dy(), iw, ih, iw, ih, s, rot, af.scale(),
                        PanelClickCache.ButtonRect.ROUNDED, 2, 0, fx));
                if (af.alpha() < 0.02f) { alphaMul = 1f; continue; }
                pose.pushPose();
                pose.translate((float) (pad + in.x()) + af.dx(), (float) (pad + in.y()) + af.dy(), 0);
                transformAbout(pose, iw, ih, rot, af.scale());
                pose.scale(s, s, 1f);
                drawInput(pose, buffers, font, def, in, phCtx, iw, ih, brightness * af.bright(), fx.hovered);
                pose.popPose();
            } else if (w instanceof ButtonWidget bw) {
                ButtonStyle st = bw.style();
                String label = animText(def, bw, resolveForDisplay(bw.label(), phCtx));
                int iconPx = Math.max(16, font.lineHeight + 1);
                RichText rich = RichText.cached(label);
                int textW = rich.measureWidth(font, iconPx);
                int textH = font.lineHeight;

                boolean hasIcon = !st.icon.isBlank();
                boolean iconOnly = hasIcon && st.iconPos == ButtonStyle.IconPos.ONLY;
                float iconW = hasIcon ? Math.max(2f, st.iconSize) : 0f;
                float contentW = iconOnly ? iconW : textW + (hasIcon ? iconW + ICON_GAP : 0f);

                // Keep button positioning pixel-accurate (avoid truncating decimals).
                float bx0 = (float) (pad + bw.x()) + af.dx();
                float by0 = (float) (pad + bw.y()) + af.dy();

                // Auto-size is recomputed every frame (not frozen into the widget) so labels whose
                // placeholders change length keep a matching button and click area.
                double baseW = bw.w() > 0 ? bw.w() : contentW + 8;
                double baseH = bw.h() > 0 ? bw.h() : Math.max(textH, iconW) + 6;
                float s = bw.scale();

                // States: hover, press and disabled change colour and size per the button's style.
                ClickSettings cs = bw.interaction();
                boolean hoverFx = cs == null || cs.hoverEffect;
                int fill = bw.color();
                if (fx.hovered) {
                    int hov = bw.colorHover();
                    // (A colorHover with zero alpha but non-zero RGB means "no hover effect".)
                    if (((hov >>> 24) & 0xFF) > 0) fill = hov;
                    else if (hov == 0 && hoverFx) fill = blendOver(fill, 0x30FFFFFF);
                }
                if (fx.press > 0f) {
                    fill = st.pressedColor != 0 ? lerpArgb(fill, st.pressedColor, fx.press)
                            : blendOver(fill, ((int) (fx.press * 0x50) << 24) | 0xFFFFFF);
                }
                int labelColor = bw.anim() == null ? 0xFFFFFFFF : bw.anim().color(0xFFFFFFFF);
                if (fx.disabled) {
                    fill = st.disabledColor != 0 ? st.disabledColor : greyOut(fill);
                    labelColor = 0xFFA0A0A0;
                }
                float k = af.scale();
                if (fx.hovered && hoverFx) k *= st.hoverScale;
                if (fx.press > 0f) k *= 1f - (1f - st.pressedScale) * fx.press;

                // Record the exact drawn geometry for click picking and hover.
                double clickW = bw.boundsW() > 0 ? bw.boundsW() : baseW;
                double clickH = bw.boundsH() > 0 ? bw.boundsH() : baseH;
                float rot = bw.rotation() + af.rotation();
                int hs = switch (st.shape) {
                    case SQUARE -> PanelClickCache.ButtonRect.RECT;
                    case CIRCLE -> PanelClickCache.ButtonRect.ELLIPSE;
                    default -> PanelClickCache.ButtonRect.ROUNDED;
                };
                double rad = st.shape == ButtonStyle.Shape.ROUNDED ? st.radius : Math.min(clickW, clickH) / 2.0;
                buttonRects.add(rect(bw.id(), bw.actionId(), bw.x() + af.dx(), bw.y() + af.dy(), baseW, baseH,
                        clickW, clickH, s, rot, k, hs, rad, 0, fx));
                if (af.alpha() < 0.02f) { alphaMul = 1f; continue; } // blinked out: clickable, not drawn

                pose.pushPose();
                pose.translate(bx0, by0, 0);
                transformAbout(pose, (float) baseW, (float) baseH, rot, k);
                pose.scale(s, s, 1.0f);

                float bright = brightness * af.bright();
                drawButtonBody(pose, st, (float) baseW, (float) baseH, fill, bright, fx.hovered && st.hoverGlow);

                // Icon and label, centred together with sub-pixel accuracy.
                float cx0 = ((float) baseW - contentW) / 2.0f;
                RenderSystem.enableDepthTest();
                if (hasIcon) {
                    float ix = st.iconPos == ButtonStyle.IconPos.RIGHT ? cx0 + textW + ICON_GAP : cx0;
                    drawButtonIcon(pose, buffers, font, st, ix, ((float) baseH - iconW) / 2f, iconW,
                            fx.disabled ? 0xFFA0A0A0 : 0xFFFFFFFF, bright);
                }
                if (!iconOnly) {
                    float tx = hasIcon && st.iconPos == ButtonStyle.IconPos.LEFT ? cx0 + iconW + ICON_GAP : cx0;
                    float ty = ((float) baseH - (float) textH) / 2.0f;
                    pose.pushPose();
                    pose.translate(tx, ty, 0);
                    rich.render(pose, buffers, font, 0, 0, labelColor, iconPx, bright);
                    pose.popPose();
                }

                pose.popPose();
            }
            alphaMul = 1f;
        }
        alphaMul = 1f;

        if (def.id != null) PanelClickCache.putButtons(def.id, buttonRects);
    }

    // ---------------------------------------------------------------- input fields

    /** The value shown in an input field for this viewer (its starting value on the editor canvas). */
    public static String inputValue(HologramDef def, InputWidget in, Placeholders.PHContext ph) {
        if (def.id == null || rawPlaceholders) return in.initialValue();
        String v = Placeholders.apply("{input." + in.name() + "}", ph);
        return v == null || v.isEmpty() ? in.initialValue() : v;
    }

    private static void drawInput(PoseStack pose, MultiBufferSource buffers, Font font, HologramDef def, InputWidget in,
                                  Placeholders.PHContext ph, float w, float h, float bright, boolean hovered) {
        float[] box = bodyOutline(ButtonStyle.Shape.ROUNDED, 2, w, h, 0);
        int fill = applyBrightnessToArgb(in.fill(), bright);
        int accent = applyBrightnessToArgb(in.accent(), bright);
        int textCol = applyBrightnessToArgb(in.textColor(), bright);
        int muted = applyBrightnessToArgb((in.textColor() & 0xFF000000) | 0x9A9AA8, bright);
        fillOutline(pose, box, w, h, fill, fill);
        fillRing(pose, box, bodyOutline(ButtonStyle.Shape.ROUNDED, 2, w, h, hovered ? 1f : 0.6f), hovered ? accent : withAlphaF(accent, 0.5f));

        String value = inputValue(def, in, ph);
        String label = resolveForDisplay(in.label(), ph);
        float ty = (h - font.lineHeight) / 2f + 1;
        float lx = 4;
        RenderSystem.enableDepthTest();
        switch (in.type()) {
            case TOGGLE -> {
                boolean on = "true".equalsIgnoreCase(value);
                if (!label.isEmpty()) drawText(pose, buffers, font, label, lx, ty, textCol);
                float sw = Math.min(18, h * 1.4f), sh = Math.min(h - 4, 9);
                float sx = w - sw - 4, sy = (h - sh) / 2f;
                pose.pushPose();
                pose.translate(sx, sy, 0);
                float[] pill = bodyOutline(ButtonStyle.Shape.PILL, 0, sw, sh, 0);
                int track = on ? accent : applyBrightnessToArgb(0xFF555560, bright);
                fillOutline(pose, pill, sw, sh, track, track);
                pose.translate(on ? sw - sh : 0, 0, 0);
                float[] knob = bodyOutline(ButtonStyle.Shape.CIRCLE, 0, sh, sh, 1);
                int white = applyBrightnessToArgb(0xFFF0F0F0, bright);
                fillOutline(pose, knob, sh, sh, white, white);
                pose.popPose();
            }
            case SLIDER -> {
                double lo = parseD(resolveForDisplay(in.min(), ph), 0), hi = parseD(resolveForDisplay(in.max(), ph), 100);
                double v = parseD(value, lo);
                float f = hi > lo ? (float) Math.max(0, Math.min(1, (v - lo) / (hi - lo))) : 0f;
                String txt = (label.isEmpty() ? "" : label + ": ") + value;
                float tx0 = lx, tw = font.width(txt);
                float trackX = 4, trackW = w - 8, trackY = h - 4;
                drawText(pose, buffers, font, txt, tx0, Math.max(1, ty - 3), textCol);
                pose.pushPose();
                pose.translate(trackX, trackY, 0);
                drawQuad(pose, Math.max(1, Math.round(trackW)), 1, applyBrightnessToArgb(0xFF555560, bright));
                drawQuad(pose, Math.max(0, Math.round(trackW * f)), 1, accent);
                pose.translate(trackW * f - 2, -1.5f, 0);
                float[] knob = bodyOutline(ButtonStyle.Shape.CIRCLE, 0, 4, 4, 0);
                int white = applyBrightnessToArgb(0xFFF0F0F0, bright);
                fillOutline(pose, knob, 4, 4, white, white);
                pose.popPose();
                if (tw > w) { /* long labels just overflow; the field can be widened */ }
            }
            case DROPDOWN -> {
                String txt = (label.isEmpty() ? "" : label + ": ") + value;
                drawText(pose, buffers, font, txt, lx, ty, textCol);
                drawText(pose, buffers, font, "\u25BC", w - 9, ty, accent);
            }
            case TEXT -> {
                if (value.isEmpty()) drawText(pose, buffers, font, label.isEmpty() ? "..." : label, lx, ty, muted);
                else drawText(pose, buffers, font, value, lx, ty, textCol);
            }
            default -> { // NUMBER
                if (!label.isEmpty()) drawText(pose, buffers, font, label, lx, ty, muted);
                drawText(pose, buffers, font, value, w - 12 - font.width(value), ty, textCol);
                drawText(pose, buffers, font, "\u00B1", w - 9, ty, accent);
            }
        }
    }

    private static void drawText(PoseStack pose, MultiBufferSource buffers, Font font, String s, float x, float y, int argb) {
        font.drawInBatch(s, x, y, argb, false, pose.last().pose(), buffers, false, 0, LightTexture.FULL_BRIGHT);
    }

    private static double parseD(String s, double def) {
        try { return Double.parseDouble(s.replace(",", "").trim()); } catch (Exception e) { return def; }
    }

    // ---------------------------------------------------------------- clickable elements

    /** Gap between a button's icon and its label (panel px). */
    private static final float ICON_GAP = 3f;

    /** Per-frame interaction state of one element for this viewer. */
    private static final class Fx {
        static final Fx NONE = new Fx(false, false, false, false, 0f, false);
        /** Gets a click/hover area this frame; clickable = false means hover tracking only. */
        final boolean tracked, clickable;
        final boolean hovered, disabled;
        /** 0..1 press animation. */
        final float press;
        /** Default hover/press/disabled look for non-button elements (buttons style themselves). */
        final boolean generic;

        Fx(boolean tracked, boolean clickable, boolean hovered, boolean disabled, float press, boolean generic) {
            this.tracked = tracked; this.clickable = clickable; this.hovered = hovered; this.disabled = disabled;
            this.press = press; this.generic = generic;
        }

        /** Fold the default hover lift, press dip and grey-out into an animation frame. */
        WidgetAnim.Frame apply(WidgetAnim.Frame f) {
            if (!generic) return f;
            float k = 1f, b = 1f, a = 1f;
            if (disabled) { b = 0.55f; a = 0.6f; }
            else {
                if (hovered) { k *= 1.03f; b *= 1.25f; }
                if (press > 0f) { k *= 1f - 0.06f * press; b *= 1f + 0.3f * press; }
            }
            if (k == 1f && b == 1f && a == 1f) return f;
            return new WidgetAnim.Frame(f.alpha() * a, f.dx(), f.dy(), f.rotation(), f.scale() * k, f.bright() * b);
        }
    }

    private static Fx fx(HologramDef def, PanelWidget w, java.util.Set<String> disabledIds) {
        if (def.id == null) return Fx.NONE; // editor canvas: no hover/press states
        boolean clickable = ClickSettings.isClickable(w);
        WidgetAnim a = w.anim();
        boolean hoverAnim = a != null && a.active() && a.onHover;
        ClickSettings tipOf = w.interaction();
        boolean hoverText = tipOf != null && tipOf.tooltip != null && !tipOf.tooltip.isBlank();
        if (!clickable && !hoverAnim && !hoverText) return Fx.NONE;
        boolean disabled = clickable && disabledIds.contains(w.id());
        boolean hovered = !disabled && PanelClickCache.isHovered(def.id, w.id());
        if (!clickable) return new Fx(true, false, hovered, false, 0f, false);
        ClickSettings cs = w.interaction();
        boolean pressAnim = cs == null || cs.pressAnimation;
        float press = disabled || !pressAnim ? 0f : PanelClickCache.pressAmount(def.id, w.id());
        boolean generic = !(w instanceof ButtonWidget) && (disabled || cs == null || cs.hoverEffect || press > 0f);
        return new Fx(true, true, hovered, disabled, press, generic);
    }

    /** Ids greyed out for this viewer (sent by the server in the snapshot). */
    private static java.util.Set<String> disabledIds(HologramDef def) {
        String s = def.meta == null ? null : def.meta.get("ui.disabled");
        if (s == null || s.isBlank()) return java.util.Set.of();
        return new java.util.HashSet<>(java.util.Arrays.asList(s.split(",")));
    }

    private static float layerScale(PanelWidget w) {
        if (w instanceof ShapeWidget sw) return sw.scale();
        if (w instanceof ImageWidget iw) return iw.scale();
        if (w instanceof ChartWidget cw) return cw.scale();
        return 1f;
    }

    /** Click area of a shape/image/chart, following the drawn outline. */
    private static PanelClickCache.ButtonRect layeredRect(PanelWidget w, WidgetAnim.Frame af, float rot, Fx fx) {
        float sc = layerScale(w);
        double ww = w instanceof ShapeWidget ? Math.round(w.w() * sc) : w.w() * sc;
        double hh = w instanceof ShapeWidget ? Math.round(w.h() * sc) : w.h() * sc;
        double x = w.x() + af.dx(), y = w.y() + af.dy();
        int hs = PanelClickCache.ButtonRect.RECT, dir = 0;
        double rad = 0;
        if (w instanceof ShapeWidget sw) {
            switch (sw.type()) {
                case OVAL, PENTAGON, HEXAGON, STAR -> hs = PanelClickCache.ButtonRect.ELLIPSE;
                case CIRCLE, SQUARE -> {
                    // Drawn as a centred square / circle inside the box.
                    double side = Math.min(ww, hh);
                    x += (ww - side) / 2.0; y += (hh - side) / 2.0;
                    ww = side; hh = side;
                    if (sw.type() == ShapeWidget.Type.CIRCLE) hs = PanelClickCache.ButtonRect.ELLIPSE;
                }
                case RRECT -> {
                    hs = PanelClickCache.ButtonRect.ROUNDED;
                    rad = cornerRadius(sw, (int) ww, (int) hh);
                }
                case TRIANGLE, RTRIANGLE -> {
                    hs = PanelClickCache.ButtonRect.TRIANGLE;
                    dir = sw.dir().ordinal();
                }
                default -> { }
            }
        }
        return rect(w.id(), ClickSettings.primaryAction(w), x, y, ww, hh, ww, hh, 1f, rot, af.scale(), hs, rad, dir, fx);
    }

    /**
     * A click rect for an element drawn as translate(x, y) -> rotate + scale k about the base box centre
     * -> scale s. The centre scale is folded into the rect's own (top-left) scale.
     */
    private static PanelClickCache.ButtonRect rect(String id, String action, double x, double y, double baseW, double baseH,
                                                   double clickW, double clickH, float s, float rot, float k,
                                                   int hitShape, double radius, int dir, Fx fx) {
        return new PanelClickCache.ButtonRect(id, action, x + baseW * (1 - k) / 2.0, y + baseH * (1 - k) / 2.0,
                baseW * k, baseH * k, clickW, clickH, s * k, rot, hitShape, radius, dir, fx.disabled,
                !fx.clickable);
    }

    /** Rotate by {@code deg} and scale by {@code k} around the centre of a w x h box at the current origin. */
    private static void transformAbout(PoseStack pose, float w, float h, float deg, float k) {
        if (deg == 0f && k == 1f) return;
        pose.translate(w / 2f, h / 2f, 0);
        if (deg != 0f) pose.mulPose(Vector3f.ZP.rotationDegrees(deg));
        if (k != 1f) pose.scale(k, k, 1f);
        pose.translate(-w / 2f, -h / 2f, 0);
    }

    private static int lerpArgb(int a, int b, float t) {
        t = clampF(t, 0f, 1f);
        int out = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int ca = (a >>> sh) & 0xFF, cb = (b >>> sh) & 0xFF;
            out |= (clamp255(Math.round(ca + (cb - ca) * t)) << sh);
        }
        return out;
    }

    /** Desaturated, darker, slightly see-through version of a colour (disabled look). */
    private static int greyOut(int argb) {
        int r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
        int l = clamp255(Math.round((0.3f * r + 0.59f * g + 0.11f * b) * 0.6f + 30));
        int a = Math.round(((argb >>> 24) & 0xFF) * 0.75f);
        return (a << 24) | (l << 16) | (l << 8) | l;
    }

    // ---------------------------------------------------------------- styled buttons

    /** Outline of a button body as x,y pairs, inset by {@code inset} px (negative = outward). */
    private static float[] bodyOutline(ButtonStyle.Shape shape, float radius, float w, float h, float inset) {
        float x = inset, y = inset, ww = w - 2 * inset, hh = h - 2 * inset;
        if (ww < 0.01f || hh < 0.01f) return new float[0];
        if (shape == ButtonStyle.Shape.CIRCLE) {
            int n = 40;
            float[] p = new float[n * 2];
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n;
                p[i * 2] = x + ww / 2f + (float) Math.cos(a) * ww / 2f;
                p[i * 2 + 1] = y + hh / 2f + (float) Math.sin(a) * hh / 2f;
            }
            return p;
        }
        if (shape == ButtonStyle.Shape.SQUARE) return new float[] { x, y, x + ww, y, x + ww, y + hh, x, y + hh };
        float r = shape == ButtonStyle.Shape.PILL ? Math.min(w, h) / 2f : radius;
        r = Math.max(0f, Math.min(r - inset, Math.min(ww, hh) / 2f));
        int seg = 8;
        float[] p = new float[4 * (seg + 1) * 2];
        float[][] corners = { { x + ww - r, y + r }, { x + ww - r, y + hh - r }, { x + r, y + hh - r }, { x + r, y + r } };
        int k = 0;
        for (int c = 0; c < 4; c++) {
            double start = -Math.PI / 2 + c * Math.PI / 2;
            for (int i = 0; i <= seg; i++) {
                double a = start + (Math.PI / 2) * i / seg;
                p[k++] = corners[c][0] + (float) Math.cos(a) * r;
                p[k++] = corners[c][1] + (float) Math.sin(a) * r;
            }
        }
        return p;
    }

    /** Fan-fill an outline; colour runs from {@code top} to {@code bottom} over the height h. */
    private static void fillOutline(PoseStack pose, float[] p, float w, float h, int top, int bottom) {
        if (p.length < 6) return;
        beginPanel2D(1f);
        RenderSystem.disableCull();
        Matrix4f m = pose.last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float cx = w / 2f, cy = h / 2f;
        int n = p.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            vtx(bb, m, cx, cy, top, bottom, h);
            vtx(bb, m, p[i * 2], p[i * 2 + 1], top, bottom, h);
            vtx(bb, m, p[j * 2], p[j * 2 + 1], top, bottom, h);
        }
        Tesselator.getInstance().end();
        RenderSystem.enableCull();
        endPanel2D();
    }

    /** Fill the band between two outlines with the same point count. */
    private static void fillRing(PoseStack pose, float[] outer, float[] inner, int argb) {
        if (outer.length < 6 || outer.length != inner.length) return;
        beginPanel2D(1f);
        RenderSystem.disableCull();
        Matrix4f m = pose.last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        int n = outer.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            vtx(bb, m, outer[i * 2], outer[i * 2 + 1], argb, argb, 1);
            vtx(bb, m, outer[j * 2], outer[j * 2 + 1], argb, argb, 1);
            vtx(bb, m, inner[j * 2], inner[j * 2 + 1], argb, argb, 1);
            vtx(bb, m, outer[i * 2], outer[i * 2 + 1], argb, argb, 1);
            vtx(bb, m, inner[j * 2], inner[j * 2 + 1], argb, argb, 1);
            vtx(bb, m, inner[i * 2], inner[i * 2 + 1], argb, argb, 1);
        }
        Tesselator.getInstance().end();
        RenderSystem.enableCull();
        endPanel2D();
    }

    private static void vtx(BufferBuilder bb, Matrix4f m, float x, float y, int top, int bottom, float h) {
        int c = top == bottom ? top : lerpArgb(top, bottom, h <= 0 ? 0f : y / h);
        bb.vertex(m, x, y, 0).color((c >>> 16) & 0xFF, (c >>> 8) & 0xFF, c & 0xFF, (c >>> 24) & 0xFF).endVertex();
    }

    private static int withAlphaF(int argb, float mul) {
        int a = clamp255(Math.round(((argb >>> 24) & 0xFF) * mul));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Button background in its style: shape, fill mode, border and hover glow. */
    private static void drawButtonBody(PoseStack pose, ButtonStyle st, float w, float h, int fill, float bright, boolean glow) {
        // Original flat square button: keep the exact old drawing.
        if (st.shape == ButtonStyle.Shape.SQUARE && st.fill == ButtonStyle.Fill.SOLID && st.borderWidth <= 0 && !glow) {
            drawQuad(pose, Math.max(0, Math.round(w)), Math.max(0, Math.round(h)), applyBrightnessToArgb(fill, bright));
            return;
        }
        int c = applyBrightnessToArgb(fill, bright);
        if (glow) {
            for (int i = 4; i >= 1; i--) {
                fillRing(pose, bodyOutline(st.shape, st.radius, w, h, -i), bodyOutline(st.shape, st.radius, w, h, -(i - 1)),
                        withAlphaF(c, 0.08f * (5 - i)));
            }
        }
        float[] body = bodyOutline(st.shape, st.radius, w, h, 0);
        switch (st.fill) {
            case SOLID -> fillOutline(pose, body, w, h, c, c);
            case GRADIENT -> fillOutline(pose, body, w, h, c, applyBrightnessToArgb(st.gradientTo, bright));
            case TRANSPARENT -> fillOutline(pose, body, w, h, withAlphaF(c, 0.2f), withAlphaF(c, 0.2f));
            case OUTLINE -> {
                float bw = Math.max(1f, st.borderWidth);
                fillRing(pose, body, bodyOutline(st.shape, st.radius, w, h, bw), c);
                return;
            }
        }
        if (st.borderWidth > 0) {
            fillRing(pose, body, bodyOutline(st.shape, st.radius, w, h, st.borderWidth),
                    applyBrightnessToArgb(st.borderColor, bright));
        }
    }

    /** An item ("minecraft:diamond") or texture icon, size x size panel px at (x, y). */
    private static void drawButtonIcon(PoseStack pose, MultiBufferSource buffers, Font font, ButtonStyle st,
                                       float x, float y, float size, int color, float bright) {
        if (st.iconIsItem()) {
            ItemStack stack = iconStack(st.icon);
            if (stack.isEmpty()) return;
            // Like the inventory draws items: centred in a size x size box (the model spans -0.5..0.5),
            // Y flipped for panel space, kept almost flat so 3D block icons don't poke through the panel.
            renderItemBox(pose, buffers, stack, x, y, size);
            return;
        }
        ResourceLocation tex = com.luminacity.advancedinteractions.client.ClientImages.resolve(st.icon);
        if (tex == null) return;
        int c = applyBrightnessToArgb(color, bright);
        drawTexture(pose, tex, x, y, size, size, c);
    }

    private static final Map<String, ItemStack> ICON_STACKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** "minecraft:diamond" -> a stack to draw (empty for unknown ids). */
    private static ItemStack iconStack(String id) {
        return ICON_STACKS.computeIfAbsent(id, k -> {
            ResourceLocation rl = ResourceLocation.tryParse(k.trim().toLowerCase(Locale.ROOT));
            Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
            return item == null || item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        });
    }

    private static void drawTexture(PoseStack pose, ResourceLocation tex, float x, float y, float w, float h, int c) {
        float a = ((c >>> 24) & 0xFF) / 255f, r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);
        Matrix4f m = pose.last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(m, x, y, 0).uv(0, 0).color(r, g, b, a).endVertex();
        bb.vertex(m, x, y + h, 0).uv(0, 1).color(r, g, b, a).endVertex();
        bb.vertex(m, x + w, y + h, 0).uv(1, 1).color(r, g, b, a).endVertex();
        bb.vertex(m, x + w, y, 0).uv(1, 0).color(r, g, b, a).endVertex();
        Tesselator.getInstance().end();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** This frame's animation state for a widget (identity when it has none). */
    private static WidgetAnim.Frame animFrame(HologramDef def, PanelWidget w) {
        WidgetAnim a = w.anim();
        if (a == null || !a.active()) return WidgetAnim.Frame.IDENTITY;
        if (a.onHover && def.id != null) {
            // Plays while the crosshair is on it, restarting each time (the editor canvas always plays it).
            if (!PanelClickCache.isHovered(def.id, w.id())) return WidgetAnim.Frame.IDENTITY;
            return a.frame(WidgetAnim.sinceAppear(def.id + "#hover", w.id()));
        }
        return a.frame(WidgetAnim.sinceAppear(def.id == null ? "canvas" : def.id, w.id()));
    }

    /** Rotate around the centre of a w x h box at the current origin. */
    private static void rotateAboutCentre(PoseStack pose, float w, float h, float deg) {
        if (deg == 0f) return;
        pose.translate(w / 2f, h / 2f, 0);
        pose.mulPose(Vector3f.ZP.rotationDegrees(deg));
        pose.translate(-w / 2f, -h / 2f, 0);
    }

    private static void drawImageWidget(PoseStack pose, ImageWidget iw, int pad, WidgetAnim.Frame af, float brightness,
                                        Placeholders.PHContext ph) {
        float w = (float) (iw.w() * iw.scale()), h = (float) (iw.h() * iw.scale());
        pose.pushPose();
        pose.translate(iw.x() + pad + af.dx(), iw.y() + pad + af.dy(), 0);
        transformAbout(pose, w, h, iw.rotation() + af.rotation(), af.scale());

        String src = resolveForDisplay(iw.source(), ph);
        ResourceLocation tex = rawPlaceholders && src.contains("{") ? null
                : com.luminacity.advancedinteractions.client.ClientImages.resolve(src);
        if (tex == null) {
            // Downloading, missing, or a placeholder source in the editor: a labelled frame.
            boolean missing = com.luminacity.advancedinteractions.client.ClientImages.missing(src);
            drawQuad(pose, (int) w, (int) h, applyBrightnessToArgb(missing ? 0x66AA2222 : 0x44FFFFFF, brightness));
            pose.popPose();
            return;
        }
        int c = applyBrightnessToArgb(iw.tint(), brightness);
        float a = ((c >>> 24) & 0xFF) / 255f, r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);
        Matrix4f m = pose.last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.vertex(m, 0, 0, 0).uv(0, 0).color(r, g, b, a).endVertex();
        bb.vertex(m, 0, h, 0).uv(0, 1).color(r, g, b, a).endVertex();
        bb.vertex(m, w, h, 0).uv(1, 1).color(r, g, b, a).endVertex();
        bb.vertex(m, w, 0, 0).uv(1, 0).color(r, g, b, a).endVertex();
        Tesselator.getInstance().end();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    private static void drawChartWidget(PoseStack pose, MultiBufferSource buffers, Font font, ChartWidget cw, int pad,
                                        WidgetAnim.Frame af, float brightness, Placeholders.PHContext ph, boolean preview) {
        float w = (float) (cw.w() * cw.scale()), h = (float) (cw.h() * cw.scale());
        double[] data = chartData(cw, ph, preview);
        pose.pushPose();
        pose.translate(cw.x() + pad + af.dx(), cw.y() + pad + af.dy(), 0);
        transformAbout(pose, w, h, cw.rotation() + af.rotation(), af.scale());

        if (data.length == 0) {
            drawQuad(pose, (int) w, 1, applyBrightnessToArgb(cw.lineColor(), brightness));
            pose.pushPose();
            pose.translate(2, h / 2f - 4, 0);
            pose.scale(0.5f, 0.5f, 1f);
            font.drawInBatch("waiting for data...", 0, 0, applyBrightnessToArgb(0xFFAAAAAA, brightness), false,
                    pose.last().pose(), buffers, false, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
            pose.popPose();
            return;
        }

        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : data) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        double dataLo = lo, dataHi = hi;
        if (!cw.autoMin()) lo = cw.min();
        if (!cw.autoMax()) hi = cw.max();
        if (hi - lo < 1e-9) { hi = lo + 1; lo = lo - (cw.autoMin() ? 1 : 0); }

        int n = data.length;
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? w / 2f : w * i / (float) (cw.points() - 1) + w * (cw.points() - n) / (float) (cw.points() - 1);
            double k = (data[i] - lo) / (hi - lo);
            ys[i] = (float) (h - Math.max(0, Math.min(1, k)) * h);
        }

        int line = applyBrightnessToArgb(cw.lineColor(), brightness);
        int fill = applyBrightnessToArgb(cw.fillColor(), brightness);
        beginPanel2D(1f);
        RenderSystem.disableCull();
        Matrix4f m = pose.last().pose();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        switch (cw.style()) {
            case BAR -> {
                float bw = Math.max(1f, w / cw.points() - 1f);
                for (int i = 0; i < n; i++) quad(bb, m, xs[i] - bw / 2f, ys[i], xs[i] + bw / 2f, h, line);
            }
            case AREA, LINE -> {
                if (cw.style() == ChartWidget.Style.AREA) {
                    for (int i = 0; i + 1 < n; i++) {
                        vtx(bb, m, xs[i], ys[i], fill); vtx(bb, m, xs[i], h, fill);
                        vtx(bb, m, xs[i + 1], h, fill); vtx(bb, m, xs[i + 1], ys[i + 1], fill);
                    }
                }
                float t = 0.75f;
                for (int i = 0; i + 1 < n; i++) {
                    float dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i];
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    if (len < 1e-4f) continue;
                    float nx = -dy / len * t, ny = dx / len * t;
                    vtx(bb, m, xs[i] + nx, ys[i] + ny, line); vtx(bb, m, xs[i] - nx, ys[i] - ny, line);
                    vtx(bb, m, xs[i + 1] - nx, ys[i + 1] - ny, line); vtx(bb, m, xs[i + 1] + nx, ys[i + 1] + ny, line);
                }
                if (n == 1) quad(bb, m, xs[0] - 1, ys[0] - 1, xs[0] + 1, ys[0] + 1, line);
            }
        }
        Tesselator.getInstance().end();
        RenderSystem.enableCull();
        endPanel2D();

        if (cw.labels()) {
            int lc = applyBrightnessToArgb(0xFFDDDDDD, brightness);
            label(pose, buffers, font, fmt(data[n - 1]), w - font.width(fmt(data[n - 1])) * 0.5f - 1, 1, lc);
            label(pose, buffers, font, fmt(cw.autoMax() ? dataHi : hi), 1, 1, 0xFF888888);
            label(pose, buffers, font, fmt(cw.autoMin() ? dataLo : lo), 1, h - 5, 0xFF888888);
        }
        pose.popPose();
    }

    private static void vtx(BufferBuilder bb, Matrix4f m, float x, float y, int argb) {
        bb.vertex(m, x, y, 0).color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF).endVertex();
    }

    private static void quad(BufferBuilder bb, Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        vtx(bb, m, x0, y0, argb); vtx(bb, m, x0, y1, argb); vtx(bb, m, x1, y1, argb); vtx(bb, m, x1, y0, argb);
    }

    private static void label(PoseStack pose, MultiBufferSource buffers, Font font, String s, float x, float y, int color) {
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(0.5f, 0.5f, 1f);
        font.drawInBatch(s, 0, 0, color, false, pose.last().pose(), buffers, false, 0, LightTexture.FULL_BRIGHT);
        pose.popPose();
    }

    private static String fmt(double v) {
        if (Math.abs(v) >= 1000) return String.format(java.util.Locale.ROOT, "%.0f", v);
        if (v == Math.rint(v)) return Long.toString((long) v);
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Samples from the server ({chart.data:<id>}); the editor canvas draws a sample curve instead. */
    private static double[] chartData(ChartWidget cw, Placeholders.PHContext ph, boolean preview) {
        if (preview) {
            int n = Math.min(cw.points(), 40);
            double[] d = new double[n];
            for (int i = 0; i < n; i++) d[i] = 10 + 6 * Math.sin(i / 4.0) + 3 * Math.sin(i / 1.7);
            return d;
        }
        String csv = Placeholders.resolveToken("chart.data:" + cw.id(), ph);
        if (csv == null || csv.isBlank()) return new double[0];
        String[] parts = csv.split(",");
        double[] out = new double[parts.length];
        int k = 0;
        for (String p : parts) {
            try { out[k++] = Double.parseDouble(p.trim()); } catch (NumberFormatException ignored) {}
        }
        return k == out.length ? out : java.util.Arrays.copyOf(out, k);
    }

    private static String animText(HologramDef def, PanelWidget w, String text) {
        WidgetAnim a = w.anim();
        if (a == null || !a.active()) return text;
        if (a.onHover && def.id != null) {
            if (!PanelClickCache.isHovered(def.id, w.id())) return text;
            return a.text(text, WidgetAnim.sinceAppear(def.id + "#hover", w.id()));
        }
        return a.text(text, WidgetAnim.sinceAppear(def.id == null ? "canvas" : def.id, w.id()));
    }

    /** Alpha-composite 'over' onto 'base' (ARGB). */
    private static int blendOver(int base, int over) {
        float oa = ((over >>> 24) & 0xFF) / 255f;
        float ba = ((base >>> 24) & 0xFF) / 255f;
        float outA = oa + ba * (1 - oa);
        if (outA <= 0f) return 0;
        int r = Math.round(((((over >>> 16) & 0xFF) * oa) + (((base >>> 16) & 0xFF) * ba * (1 - oa))) / outA);
        int g = Math.round(((((over >>> 8) & 0xFF) * oa) + (((base >>> 8) & 0xFF) * ba * (1 - oa))) / outA);
        int b = Math.round((((over & 0xFF) * oa) + ((base & 0xFF) * ba * (1 - oa))) / outA);
        return (clamp255(Math.round(outA * 255)) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    /**
     * Renders a ShapeWidget into the current local space (0..w, 0..h).
     * If a border is configured, we render the border shape first, then inset the fill.
     */
    private static void renderShape(PoseStack pose, int w, int h, ShapeWidget sw, float brightness) {
        double bw = sw.borderWidth();
        boolean hasBorder = bw > 0.0 && ((sw.border() >>> 24) & 0xFF) > 0;

        if (!hasBorder) {
            drawShape(pose, w, h, sw, applyBrightnessToArgb(sw.fill(), brightness));
            return;
        }

        // Fill the whole shape, then stroke an even-width border inside its real outline
        // (an inset copy of the shape doesn't line up for arrows, chevrons, stars...).
        drawShape(pose, w, h, sw, applyBrightnessToArgb(sw.fill(), brightness));
        List<V2> outline = shapeOutline(sw, w, h);
        float bwf = (float) Math.min(bw, Math.min(w, h) / 2.0);
        strokeInside(pose, outline, bwf, applyBrightnessToArgb(sw.border(), brightness));
        RenderSystem.enableDepthTest();
    }

    /** When non-null, drawPolygon/drawFan record their outline here instead of drawing (see shapeOutline). */
    private static List<V2> capture = null;

    /** The outline of a shape as drawn by drawShape (w x h box at the origin). */
    private static List<V2> shapeOutline(ShapeWidget sw, float w, float h) {
        int wi = Math.max(0, Math.round(w)), hi = Math.max(0, Math.round(h));
        float cx = wi / 2f, cy = hi / 2f;
        switch (sw.type()) {
            case RECT, LINE -> { return rectPts(0, 0, wi, hi); }
            case SQUARE -> {
                int side = Math.min(wi, hi);
                return rectPts((wi - side) / 2f, (hi - side) / 2f, side, side);
            }
            case TRIANGLE -> { return trianglePts(0, 0, wi, hi, sw.dir()); }
            case ARROW -> {
                List<V2> p = new ArrayList<>(7);
                switch (sw.dir()) {
                    case LEFT -> {
                        int hw = Math.max(4, wi / 3);
                        p.add(new V2(0, cy)); p.add(new V2(hw, 0)); p.add(new V2(hw, hi * 0.25f)); p.add(new V2(wi, hi * 0.25f));
                        p.add(new V2(wi, hi * 0.75f)); p.add(new V2(hw, hi * 0.75f)); p.add(new V2(hw, hi));
                    }
                    case UP -> {
                        int hh = Math.max(4, hi / 3);
                        p.add(new V2(cx, 0)); p.add(new V2(wi, hh)); p.add(new V2(wi * 0.75f, hh)); p.add(new V2(wi * 0.75f, hi));
                        p.add(new V2(wi * 0.25f, hi)); p.add(new V2(wi * 0.25f, hh)); p.add(new V2(0, hh));
                    }
                    case DOWN -> {
                        int hh = Math.max(4, hi / 3);
                        p.add(new V2(wi * 0.25f, 0)); p.add(new V2(wi * 0.75f, 0)); p.add(new V2(wi * 0.75f, hi - hh)); p.add(new V2(wi, hi - hh));
                        p.add(new V2(cx, hi)); p.add(new V2(0, hi - hh)); p.add(new V2(wi * 0.25f, hi - hh));
                    }
                    default -> {
                        int hw = Math.max(4, wi / 3);
                        p.add(new V2(0, hi * 0.25f)); p.add(new V2(wi - hw, hi * 0.25f)); p.add(new V2(wi - hw, 0)); p.add(new V2(wi, cy));
                        p.add(new V2(wi - hw, hi)); p.add(new V2(wi - hw, hi * 0.75f)); p.add(new V2(0, hi * 0.75f));
                    }
                }
                return p;
            }
            default -> {
                // Everything else is drawn through drawPolygon/drawFan: record instead of drawing.
                List<V2> out = new ArrayList<>();
                capture = out;
                try {
                    drawShape(null, w, h, sw, 0);
                } catch (RuntimeException e) {
                    out.clear(); // a helper that draws directly (e.g. a tiny rounded triangle falling back to a plain one)
                } finally {
                    capture = null;
                }
                if (out.size() < 3) return sw.type() == ShapeWidget.Type.RTRIANGLE ? trianglePts(0, 0, wi, hi, sw.dir()) : rectPts(0, 0, wi, hi);
                return out;
            }
        }
    }

    private static List<V2> rectPts(float x, float y, float w, float h) {
        return new ArrayList<>(List.of(new V2(x, y), new V2(x + w, y), new V2(x + w, y + h), new V2(x, y + h)));
    }

    private static List<V2> trianglePts(float x, float y, int w, int h, ShapeWidget.Dir dir) {
        float x1 = x + w, y1 = y + h, cx = x + w / 2f, cy = y + h / 2f;
        return switch (dir) {
            case UP -> new ArrayList<>(List.of(new V2(cx, y), new V2(x1, y1), new V2(x, y1)));
            case DOWN -> new ArrayList<>(List.of(new V2(cx, y1), new V2(x, y), new V2(x1, y)));
            case LEFT -> new ArrayList<>(List.of(new V2(x, cy), new V2(x1, y), new V2(x1, y1)));
            default -> new ArrayList<>(List.of(new V2(x1, cy), new V2(x, y1), new V2(x, y)));
        };
    }

    /**
     * Stroke a band of width {@code bw} just inside a closed outline, with mitred corners (limited so
     * sharp tips don't spike). Works for concave outlines like chevrons, arrows and stars.
     */
    private static void strokeInside(PoseStack pose, List<V2> raw, float bw, int argb) {
        if (raw == null || bw <= 0) return;
        List<V2> p = new ArrayList<>(raw.size());
        for (V2 v : raw) {
            V2 last = p.isEmpty() ? null : p.get(p.size() - 1);
            if (last == null || Math.abs(last.x - v.x) + Math.abs(last.y - v.y) > 1e-3f) p.add(v);
        }
        while (p.size() > 2) {
            V2 a = p.get(0), b = p.get(p.size() - 1);
            if (Math.abs(a.x - b.x) + Math.abs(a.y - b.y) > 1e-3f) break;
            p.remove(p.size() - 1);
        }
        int n = p.size();
        if (n < 3) return;
        float area2 = 0;
        for (int i = 0; i < n; i++) {
            V2 a = p.get(i), b = p.get((i + 1) % n);
            area2 += a.x * b.y - b.x * a.y;
        }
        float sgn = area2 > 0 ? 1f : -1f; // inward normal of edge (dx,dy) is sgn * (-dy, dx)
        float[] outer = new float[n * 2], inner = new float[n * 2];
        for (int i = 0; i < n; i++) {
            V2 prev = p.get((i - 1 + n) % n), cur = p.get(i), next = p.get((i + 1) % n);
            float e0x = cur.x - prev.x, e0y = cur.y - prev.y, e1x = next.x - cur.x, e1y = next.y - cur.y;
            float l0 = (float) Math.hypot(e0x, e0y), l1 = (float) Math.hypot(e1x, e1y);
            e0x /= l0; e0y /= l0; e1x /= l1; e1y /= l1;
            float n0x = -e0y * sgn, n0y = e0x * sgn, n1x = -e1y * sgn, n1y = e1x * sgn;
            float mx = n0x + n1x, my = n0y + n1y;
            float ml = (float) Math.hypot(mx, my);
            if (ml < 1e-4f) { mx = n0x; my = n0y; } else { mx /= ml; my /= ml; }
            float cos = mx * n0x + my * n0y;
            float len = bw / Math.max(cos, 0.3f);
            outer[i * 2] = cur.x; outer[i * 2 + 1] = cur.y;
            inner[i * 2] = cur.x + mx * len; inner[i * 2 + 1] = cur.y + my * len;
        }
        fillRing(pose, outer, inner, argb);
    }

    private static void drawShape(PoseStack pose, float w, float h, ShapeWidget sw, int argb) {
        int wi = Math.max(0, Math.round(w));
        int hi = Math.max(0, Math.round(h));

        switch (sw.type()) {
            case RECT, LINE -> drawQuad(pose, wi, hi, argb);

            case RRECT -> {
                float r = cornerRadius(sw, wi, hi);
                drawRoundedRect(pose, wi, hi, r, argb);
            }

            case SQUARE -> {
                int side = Math.min(wi, hi);
                int ox = (wi - side) / 2;
                int oy = (hi - side) / 2;
                drawQuadAt(pose, ox, oy, side, side, argb);
            }

            case TRIANGLE -> drawTriangleAt(pose, 0, 0, wi, hi, argb, sw.dir());

            case RTRIANGLE -> {
                float r = cornerRadius(sw, wi, hi);
                drawRoundedTriangleAt(pose, 0, 0, wi, hi, r, argb, sw.dir());
            }

            case ARROW -> drawArrowAt(pose, 0, 0, wi, hi, argb, sw.dir());

            case CHEVRON -> drawChevronAt(pose, 0, 0, wi, hi, argb, sw.dir());

            case OVAL -> drawOvalAt(pose, 0, 0, wi, hi, argb);

            case CIRCLE -> {
                int side = Math.min(wi, hi);
                int ox = (wi - side) / 2;
                int oy = (hi - side) / 2;
                drawOvalAt(pose, ox, oy, side, side, argb);
            }

            case PENTAGON -> drawRegularPolygonAt(pose, 0, 0, wi, hi, 5, argb);
            case HEXAGON -> drawRegularPolygonAt(pose, 0, 0, wi, hi, 6, argb);

            case STAR -> drawStarAt(pose, 0, 0, wi, hi, argb);

            case ROUTE_SHIELD -> drawRouteShieldAt(pose, 0, 0, wi, hi, argb);
            case INTERSTATE_SHIELD -> drawInterstateShieldAt(pose, 0, 0, wi, hi, argb);
        }
    }

      /**
     * US Route / Highway shield silhouette.
     * This is a stylized polygon approximation (not a bitmap) so it scales cleanly.
     */
    private static void drawRouteShieldAt(PoseStack pose, float x, float y, int w, int h, int argb) {
        // Normalized control points tuned to resemble a US Route shield.
        float x0 = x, y0 = y;
        float x1 = x + w, y1 = y + h;

        float cx = x + w * 0.5f;

        // Key heights
        float top = y0;
        float top2 = y0 + h * 0.07f;
        float shoulderY = y0 + h * 0.22f;
        float midY = y0 + h * 0.68f;
        float bottomY = y0 + h * 0.95f;

        // Key widths
        float topInset = w * 0.18f;
        float shoulderInset = w * 0.06f;
        float midInset = w * 0.22f;

        // Build clockwise ring.
        List<V2> pts = new ArrayList<>(32);

        // Top crown (slight arch)
        pts.add(new V2(cx, top));
        pts.add(new V2(x1 - topInset, top2));
        pts.add(new V2(x1 - shoulderInset, shoulderY));

        // Right side curve down
        pts.add(new V2(x1 - midInset * 0.55f, y0 + h * 0.45f));
        pts.add(new V2(x1 - midInset, midY));

        // Bottom point
        pts.add(new V2(cx, bottomY));

        // Left side curve up
        pts.add(new V2(x0 + midInset, midY));
        pts.add(new V2(x0 + midInset * 0.55f, y0 + h * 0.45f));
        pts.add(new V2(x0 + shoulderInset, shoulderY));
        pts.add(new V2(x0 + topInset, top2));

        drawPolygon(pose, pts, argb);
    }

    /**
     * Interstate shield silhouette.
     * Stylized polygon approximation with a "crown" top and pointed bottom.
     */
    private static void drawInterstateShieldAt(PoseStack pose, float x, float y, int w, int h, int argb) {
        float x0 = x, y0 = y;
        float x1 = x + w, y1 = y + h;
        float cx = x + w * 0.5f;

        float top = y0;
        float crownY = y0 + h * 0.10f;
        float topBandY = y0 + h * 0.22f;
        float sideY = y0 + h * 0.40f;
        float midY = y0 + h * 0.72f;
        float bottomY = y0 + h * 0.95f;

        float crownInset = w * 0.12f;
        float topCornerInset = w * 0.08f;
        float sideInset = w * 0.04f;
        float midInset = w * 0.20f;

        List<V2> pts = new ArrayList<>(40);

        // Crown top (two humps)
        pts.add(new V2(x0 + crownInset, crownY));
        pts.add(new V2(cx - w * 0.14f, top));
        pts.add(new V2(cx, crownY));
        pts.add(new V2(cx + w * 0.14f, top));
        pts.add(new V2(x1 - crownInset, crownY));

        // Upper corners into top band
        pts.add(new V2(x1 - topCornerInset, topBandY));

        // Right side
        pts.add(new V2(x1 - sideInset, sideY));
        pts.add(new V2(x1 - midInset, midY));

        // Bottom point
        pts.add(new V2(cx, bottomY));

        // Left side
        pts.add(new V2(x0 + midInset, midY));
        pts.add(new V2(x0 + sideInset, sideY));

        // Back to top band
        pts.add(new V2(x0 + topCornerInset, topBandY));

        drawPolygon(pose, pts, argb);
    }

    private static float cornerRadius(ShapeWidget sw, int w, int h) {
        float r = (float) sw.cornerRadius();
        float max = Math.min(w, h) / 2f;
        if (r <= 0) {
            // Sensible default: ~25% of the smaller dimension
            r = Math.min(w, h) * 0.25f;
        }
        if (r > max) r = max;
        if (r < 0) r = 0;
        return r;
    }

    private record V2(float x, float y) {}

    private static List<V2> ensureFrontWinding(List<V2> pts) {
    if (pts == null || pts.size() < 3) return pts;

    float area2 = 0f;
    for (int i = 0; i < pts.size(); i++) {
        V2 a = pts.get(i);
        V2 b = pts.get((i + 1) % pts.size());
        area2 += (a.x * b.y) - (b.x * a.y);
    }

    // In our Y-down panel space, negative signed area matches the front-facing winding.
    if (area2 <= 0f) return pts;

    // Reverse without mutating the original list (some shapes reuse lists).
    List<V2> rev = new ArrayList<>(pts.size());
    for (int i = pts.size() - 1; i >= 0; i--) rev.add(pts.get(i));
    return rev;
}

    private static void drawPolygon(PoseStack pose, List<V2> pts, int argb) {
        if (pts == null || pts.size() < 3) return;
        if (capture != null) { capture.addAll(pts); return; }
            pts = ensureFrontWinding(pts);
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8)  & 0xFF) / 255f;
        float b = ((argb)        & 0xFF) / 255f;

        beginPanel2D(a);
        Matrix4f m = pose.last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        V2 v0 = pts.get(0);
        for (int i = 1; i < pts.size() - 1; i++) {
            V2 v1 = pts.get(i);
            V2 v2 = pts.get(i + 1);
            bb.vertex(m, v0.x, v0.y, 0).color(r, g, b, a).endVertex();
            bb.vertex(m, v1.x, v1.y, 0).color(r, g, b, a).endVertex();
            bb.vertex(m, v2.x, v2.y, 0).color(r, g, b, a).endVertex();
        }
        tess.end();
        endPanel2D();
        
    }

    /**
     * Triangle-fan fill from a chosen center point. Works well for concave shapes
     * when the center is inside the shape (e.g., stars).
     */
    private static void drawFan(PoseStack pose, V2 center, List<V2> ring, int argb) {
        if (ring == null || ring.size() < 3) return;
        if (capture != null) { capture.addAll(ring); return; }
            ring = ensureFrontWinding(ring);
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8)  & 0xFF) / 255f;
        float b = ((argb)        & 0xFF) / 255f;

        beginPanel2D(a);
        Matrix4f m = pose.last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < ring.size(); i++) {
            V2 v1 = ring.get(i);
            V2 v2 = ring.get((i + 1) % ring.size());
            bb.vertex(m, center.x, center.y, 0).color(r, g, b, a).endVertex();
            bb.vertex(m, v1.x, v1.y, 0).color(r, g, b, a).endVertex();
            bb.vertex(m, v2.x, v2.y, 0).color(r, g, b, a).endVertex();
        }
        tess.end();
        endPanel2D();
        
    }

    private static void drawRoundedRect(PoseStack pose, int w, int h, float radius, int argb) {
        float r = Math.max(0, Math.min(radius, Math.min(w, h) / 2f));
        int seg = 6; // per corner

        List<V2> pts = new ArrayList<>(seg * 4 + 4);
        // Clockwise, starting at top-right arc start (w - r, 0)
        addArc(pts, w - r, r, r, (float) (-Math.PI / 2), 0f, seg);
        addArc(pts, w - r, h - r, r, 0f, (float) (Math.PI / 2), seg);
        addArc(pts, r, h - r, r, (float) (Math.PI / 2), (float) Math.PI, seg);
        addArc(pts, r, r, r, (float) Math.PI, (float) (Math.PI * 1.5), seg);

        drawPolygon(pose, pts, argb);
    }

    private static void addArc(List<V2> out, float cx, float cy, float r, float a0, float a1, int seg) {
        if (r <= 0.001f) {
            out.add(new V2(cx, cy));
            return;
        }
        for (int i = 0; i <= seg; i++) {
            float t = i / (float) seg;
            float a = a0 + (a1 - a0) * t;
            out.add(new V2(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r));
        }
    }

    private static void drawOvalAt(PoseStack pose, float x, float y, int w, int h, int argb) {
        int seg = 32;
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float rx = w / 2f;
        float ry = h / 2f;

        List<V2> pts = new ArrayList<>(seg);
        for (int i = 0; i < seg; i++) {
            float a = (float) (i * (Math.PI * 2) / seg);
            pts.add(new V2(cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry));
        }
        drawPolygon(pose, pts, argb);
    }

    private static void drawRegularPolygonAt(PoseStack pose, float x, float y, int w, int h, int sides, int argb) {
        int n = Math.max(3, Math.min(12, sides));
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float rx = w / 2f;
        float ry = h / 2f;
        float start = (float) (-Math.PI / 2); // flat-top feel

        List<V2> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float a = start + (float) (i * (Math.PI * 2) / n);
            pts.add(new V2(cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry));
        }
        drawPolygon(pose, pts, argb);
    }

    private static void drawChevronAt(PoseStack pose, float x, float y, int w, int h, int argb, ShapeWidget.Dir dir) {
        // Build a "V" chevron shape (6 points), then rotate based on dir.
        float cx = x + w / 2f;
        float cy = y + h / 2f;

        List<V2> base = new ArrayList<>(6);
        base.add(new V2(x, y));
        base.add(new V2(x + w * 0.6f, y));
        base.add(new V2(x + w, y + h * 0.5f));
        base.add(new V2(x + w * 0.6f, y + h));
        base.add(new V2(x, y + h));
        base.add(new V2(x + w * 0.4f, y + h * 0.5f));

        float rot;
        switch (dir) {
            case UP -> rot = (float) (-Math.PI / 2);
            case DOWN -> rot = (float) (Math.PI / 2);
            case LEFT -> rot = (float) Math.PI;
            case RIGHT -> rot = 0f;
            default -> rot = 0f;
        }

        if (rot != 0f) {
            List<V2> pts = new ArrayList<>(base.size());
            for (V2 p : base) {
                float dx = p.x - cx;
                float dy = p.y - cy;
                float rx = (float) (dx * Math.cos(rot) - dy * Math.sin(rot));
                float ry = (float) (dx * Math.sin(rot) + dy * Math.cos(rot));
                pts.add(new V2(cx + rx, cy + ry));
            }
            drawPolygon(pose, pts, argb);
        } else {
            drawPolygon(pose, base, argb);
        }
    }

    private static void drawStarAt(PoseStack pose, float x, float y, int w, int h, int argb) {
        // 5-point star (10 vertices) around center.
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float orx = w / 2f;
        float ory = h / 2f;
        float irx = orx * 0.5f;
        float iry = ory * 0.5f;
        float start = (float) (-Math.PI / 2);

        List<V2> pts = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            float a = start + (float) (i * (Math.PI * 2) / 10f);
            boolean outer = (i % 2) == 0;
            float rx = outer ? orx : irx;
            float ry = outer ? ory : iry;
            pts.add(new V2(cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry));
        }
        drawFan(pose, new V2(cx, cy), pts, argb);
    }

    private static void drawRoundedTriangleAt(PoseStack pose, float x, float y, int w, int h, float radius, int argb, ShapeWidget.Dir dir) {
        float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
        float cx = x + w / 2f, cy = y + h / 2f;

        // Triangle vertices (A,B,C) in a consistent loop order
        V2 A, B, C;
        switch (dir) {
            case UP -> {
                A = new V2(cx, y0);
                B = new V2(x1, y1);
                C = new V2(x0, y1);
            }
            case DOWN -> {
                A = new V2(cx, y1);
                B = new V2(x0, y0);
                C = new V2(x1, y0);
            }
            case LEFT -> {
                A = new V2(x0, cy);
                B = new V2(x1, y0);
                C = new V2(x1, y1);
            }
            case RIGHT -> {
                A = new V2(x1, cy);
                B = new V2(x0, y1);
                C = new V2(x0, y0);
            }
            default -> {
                A = new V2(x1, cy);
                B = new V2(x0, y1);
                C = new V2(x0, y0);
            }
        }

        // Build a true rounded-corner triangle by filleting each vertex.
        // This keeps the rounding *inside* the triangle (not bulging outward).

        List<V2> verts = List.of(A, B, C);

        // Clamp radius so it can't eat the entire edge.
        float minEdge = Math.min(dist(A, B), Math.min(dist(B, C), dist(C, A)));
        float r = Math.max(0, Math.min(radius, minEdge * 0.45f));
        if (r <= 0.5f) {
            drawTriangleAt(pose, x, y, w, h, argb, dir);
            return;
        }

        CornerRound cA = filletCorner(verts, 0, r);
        CornerRound cB = filletCorner(verts, 1, r);
        CornerRound cC = filletCorner(verts, 2, r);

        // If anything degenerates, fall back to sharp triangle.
        if (!cA.ok || !cB.ok || !cC.ok) {
            drawTriangleAt(pose, x, y, w, h, argb, dir);
            return;
        }

        // Walk the outline in vertex order A->B->C, using:
        // edge segment (prevCorner.exit -> nextCorner.enter) + arc (nextCorner.enter -> nextCorner.exit)
        List<V2> outline = new ArrayList<>();

        // Start on edge A->B at A's exit point.
        outline.add(cA.exit);

        // Edge A->B then arc around B
        outline.add(cB.enter);
        addArc(outline, cB, false);

        // Edge B->C then arc around C
        outline.add(cC.enter);
        addArc(outline, cC, false);

        // Edge C->A then arc around A
        outline.add(cA.enter);
        addArc(outline, cA, false);

        drawPolygon(pose, outline, argb);
    }

    private record CornerRound(boolean ok, V2 enter, V2 exit, V2 center, float radius, float aEnter, float aExit, int seg) {}

    /**
     * Computes a fillet (rounded) corner for a convex polygon at vertex index i.
     * For our triangle use-case, verts has size 3.
     */
    private static CornerRound filletCorner(List<V2> verts, int i, float desiredR) {
        int n = verts.size();
        V2 V = verts.get(i);
        V2 Prev = verts.get((i - 1 + n) % n);
        V2 Next = verts.get((i + 1) % n);

        V2 e1 = sub(Prev, V);
        V2 e2 = sub(Next, V);

        float len1 = (float) Math.sqrt(e1.x * e1.x + e1.y * e1.y);
        float len2 = (float) Math.sqrt(e2.x * e2.x + e2.y * e2.y);
        if (len1 < 1e-3f || len2 < 1e-3f) return new CornerRound(false, V, V, V, 0, 0, 0, 0);

        V2 u1 = new V2(e1.x / len1, e1.y / len1);
        V2 u2 = new V2(e2.x / len2, e2.y / len2);

        float dot = (u1.x * u2.x) + (u1.y * u2.y);
        dot = Math.max(-1f, Math.min(1f, dot));
        float theta = (float) Math.acos(dot); // interior angle
        if (theta < 1e-3f || Math.abs(theta - Math.PI) < 1e-3f) return new CornerRound(false, V, V, V, 0, 0, 0, 0);

        float tanHalf = (float) Math.tan(theta / 2f);
        float sinHalf = (float) Math.sin(theta / 2f);
        if (tanHalf < 1e-3f || sinHalf < 1e-3f) return new CornerRound(false, V, V, V, 0, 0, 0, 0);

        // Limit how far we travel along each edge to avoid overlap.
        float maxT = Math.min(len1, len2) * 0.45f;
        float maxR = maxT * tanHalf;
        float r = Math.max(0.0f, Math.min(desiredR, maxR));
        float t = r / tanHalf;

        // Tangency points on each edge.
        V2 enter = add(V, new V2(u1.x * t, u1.y * t)); // along V->Prev
        V2 exit  = add(V, new V2(u2.x * t, u2.y * t)); // along V->Next

        // Arc center along the internal angle bisector.
        V2 bis = new V2(u1.x + u2.x, u1.y + u2.y);
        float bisLen = (float) Math.sqrt(bis.x * bis.x + bis.y * bis.y);
        if (bisLen < 1e-3f) return new CornerRound(false, enter, exit, V, r, 0, 0, 0);
        bis = new V2(bis.x / bisLen, bis.y / bisLen);

        float centerDist = r / sinHalf;
        V2 center = add(V, new V2(bis.x * centerDist, bis.y * centerDist));

        float aEnter = (float) Math.atan2(enter.y - center.y, enter.x - center.x);
        float aExit  = (float) Math.atan2(exit.y - center.y,  exit.x - center.x);

        int seg = Math.max(4, Math.min(14, Math.round(r * 0.6f) + 4));
        return new CornerRound(true, enter, exit, center, r, aEnter, aExit, seg);
    }

    /** Adds arc points for a computed corner. */
    private static void addArc(List<V2> out, CornerRound c, boolean includeFirst) {
        // Normalize delta to the shortest sweep.
        float delta = c.aExit - c.aEnter;
        while (delta <= -Math.PI) delta += (float) (Math.PI * 2);
        while (delta > Math.PI) delta -= (float) (Math.PI * 2);

        int start = includeFirst ? 0 : 1;
        for (int i = start; i <= c.seg; i++) {
            float t = i / (float) c.seg;
            float a = c.aEnter + delta * t;
            out.add(new V2(
                    c.center.x + (float) Math.cos(a) * c.radius,
                    c.center.y + (float) Math.sin(a) * c.radius
            ));
        }
    }

    private static float dist(V2 a, V2 b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static V2 sub(V2 a, V2 b) {
        return new V2(a.x - b.x, a.y - b.y);
    }

    private static V2 norm(V2 v, float len) {
        float l = (float) Math.sqrt(v.x * v.x + v.y * v.y);
        if (l < 0.0001f) return new V2(0, 0);
        return new V2((v.x / l) * len, (v.y / l) * len);
    }

    private static V2 add(V2 a, V2 d) {
        return new V2(a.x + d.x, a.y + d.y);
    }

    /**
     * Adds arc points around vertex 'v' between edges (v->prev) and (v->next).
     * This is a simple, visually pleasing rounding for triangles.
     */
    private static void addRoundedCornerArc(List<V2> out, V2 v, V2 prev, V2 next, float r, int seg) {
        float ux = prev.x - v.x;
        float uy = prev.y - v.y;
        float vx = next.x - v.x;
        float vy = next.y - v.y;

        float ulen = (float) Math.sqrt(ux * ux + uy * uy);
        float vlen = (float) Math.sqrt(vx * vx + vy * vy);
        if (ulen < 0.001f || vlen < 0.001f) return;
        ux /= ulen; uy /= ulen;
        vx /= vlen; vy /= vlen;

        float a0 = (float) Math.atan2(uy, ux);
        float a1 = (float) Math.atan2(vy, vx);

        // Normalize delta to the shortest sweep
        float delta = a1 - a0;
        while (delta <= -Math.PI) delta += (float) (Math.PI * 2);
        while (delta > Math.PI) delta -= (float) (Math.PI * 2);

        for (int i = 0; i <= seg; i++) {
            float t = i / (float) seg;
            float a = a0 + delta * t;
            out.add(new V2(v.x + (float) Math.cos(a) * r, v.y + (float) Math.sin(a) * r));
        }
    }

    private static void drawTriangleAt(PoseStack pose, float x, float y, int w, int h, int argb, ShapeWidget.Dir dir) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8)  & 0xFF) / 255f;
        float b = ((argb)        & 0xFF) / 255f;

        beginPanel2D(a);
        Matrix4f m = pose.last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
        float cx = x + w / 2f, cy = y + h / 2f;

        // 3 vertices for a direction-pointing triangle
        switch (dir) {
            case UP -> {
                bb.vertex(m, cx, y0, 0).color(r, g, b, a).endVertex();
                bb.vertex(m, x0, y1, 0).color(r, g, b, a).endVertex();
                bb.vertex(m, x1, y1, 0).color(r, g, b, a).endVertex();
            }
            case DOWN -> {
                bb.vertex(m, cx, y1, 0).color(r, g, b, a).endVertex();
                bb.vertex(m, x1, y0, 0).color(r, g, b, a).endVertex();
                bb.vertex(m, x0, y0, 0).color(r, g, b, a).endVertex();
            }
            case LEFT -> {
                bb.vertex(m, x0, cy, 0).color(r, g, b, a).endVertex();
                bb.vertex(m, x1, y1, 0).color(r, g, b, a).endVertex();
                bb.vertex(m, x1, y0, 0).color(r, g, b, a).endVertex();
            }
            case RIGHT -> {
                bb.vertex(m, x1, cy, 0).color(r, g, b, a).endVertex();
                bb.vertex(m, x0, y0, 0).color(r, g, b, a).endVertex();
                bb.vertex(m, x0, y1, 0).color(r, g, b, a).endVertex();
            }
        }

        tess.end();
        endPanel2D();
        
    }

    private static void drawArrowAt(PoseStack pose, float x, float y, int w, int h, int argb, ShapeWidget.Dir dir) {
        // Simple compound: a shaft rect + a head triangle
        int shaftColor = argb;
        int headColor = argb;

        switch (dir) {
            case RIGHT -> {
                int headW = Math.max(4, w / 3);
                drawQuadAt(pose, x, y + (h * 0.25f), w - headW, h * 0.5f, shaftColor);
                drawTriangleAt(pose, x + (w - headW), y, headW, h, headColor, ShapeWidget.Dir.RIGHT);
            }
            case LEFT -> {
                int headW = Math.max(4, w / 3);
                drawQuadAt(pose, x + headW, y + (h * 0.25f), w - headW, h * 0.5f, shaftColor);
                drawTriangleAt(pose, x, y, headW, h, headColor, ShapeWidget.Dir.LEFT);
            }
            case UP -> {
                int headH = Math.max(4, h / 3);
                drawQuadAt(pose, x + (w * 0.25f), y + headH, w * 0.5f, h - headH, shaftColor);
                drawTriangleAt(pose, x, y, w, headH, headColor, ShapeWidget.Dir.UP);
            }
            case DOWN -> {
                int headH = Math.max(4, h / 3);
                drawQuadAt(pose, x + (w * 0.25f), y, w * 0.5f, h - headH, shaftColor);
                drawTriangleAt(pose, x, y + (h - headH), w, headH, headColor, ShapeWidget.Dir.DOWN);
            }
        }
    }

    private static void drawQuad(PoseStack pose, int w, int h, int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255f;
        float r = ((argb >>> 16) & 0xFF) / 255f;
        float g = ((argb >>> 8)  & 0xFF) / 255f;
        float b = ((argb)        & 0xFF) / 255f;

        beginPanel2D(a);
        Matrix4f m = pose.last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.vertex(m, 0, 0, 0).color(r, g, b, a).endVertex();
        bb.vertex(m, 0, h, 0).color(r, g, b, a).endVertex();
        bb.vertex(m, w, h, 0).color(r, g, b, a).endVertex();
        bb.vertex(m, w, 0, 0).color(r, g, b, a).endVertex();
        tess.end();
        endPanel2D();
        RenderSystem.enableDepthTest();
    }

    private static void drawQuadAt(PoseStack pose, float x, float y, float w, float h, int argb) {
        pose.pushPose();
        pose.translate(x, y, 0);
        drawQuad(pose, (int) w, (int) h, argb);
        pose.popPose();
    }

    // ---------------------------------------------------------------------
    // Rich text (inline item/entity icons + gradients)
    // Syntax:
    //   :iron_ore:
    //   :minecraft:iron_ore:
    //   :modid:item_name:
    //   :minecraft:zombie:   (entity -> spawn egg icon if available)
    // ---------------------------------------------------------------------

    private static final class RichText {
        private static final int ICON_GAP = 1;

        // Default inline icon sizing: the icon fills a box of iconPx * DEFAULT_*_MULT * size@<mult> panel
        // pixels, centred on the text line, and the line reserves exactly that width.
        // Items: 0.625 keeps the size they always had (the old renderer drew them at 1/16 of a x10 box).
        private static final float DEFAULT_ITEM_MULT  = 0.625f;
        private static final float DEFAULT_BLOCK_MULT = 1.0f;
        private static final float DEFAULT_ENTITY_MULT = 1.0f;
        private final List<Part> parts;

        private RichText(List<Part> parts) {
            this.parts = parts;
        }

        // Parsing resolves icon tokens against the item/block/entity registries (expensive), so parsed
        // results are cached by their resolved string instead of re-parsed for every widget every frame.
        private static final int CACHE_MAX = 1024;
        private static final Map<String, RichText> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, RichText> eldest) {
                return size() > CACHE_MAX;
            }
        };

        public static RichText cached(String raw) {
            String key = raw == null ? "" : raw;
            synchronized (CACHE) {
                RichText hit = CACHE.get(key);
                if (hit != null) return hit;
            }
            RichText parsed = parse(key);
            synchronized (CACHE) {
                CACHE.put(key, parsed);
            }
            return parsed;
        }

        public static RichText parse(String raw) {
            if (raw == null || raw.isEmpty()) {
                return new RichText(List.of());
            }

            List<Part> out = new ArrayList<>();
            StringBuilder buf = new StringBuilder();

            int len = raw.length();
            int i = 0;
            int hex = 0; // active §#RRGGBB colour (0 = none)
            while (i < len) {
                char c = raw.charAt(i);
                // Hex colour §#RRGGBB: vanilla can't draw it from a plain string, so it splits the text.
                if (c == '§' && i + 1 < len) {
                    char code = Character.toLowerCase(raw.charAt(i + 1));
                    if (code == '#' && i + 8 <= len && com.luminacity.advancedinteractions.util.TextFmt.isHex(raw, i + 2)) {
                        if (buf.length() > 0) { out.add(new TextPart(buf.toString(), hex)); buf.setLength(0); }
                        hex = 0xFF000000 | Integer.parseInt(raw.substring(i + 2, i + 8), 16);
                        i += 8;
                        continue;
                    }
                    if (hex != 0 && ("0123456789abcdef".indexOf(code) >= 0 || code == 'r')) {
                        // A normal colour code ends the hex colour.
                        if (buf.length() > 0) { out.add(new TextPart(buf.toString(), hex)); buf.setLength(0); }
                        hex = 0;
                    }
                }
                if (c == ':') {
                    // Gradient block:
                    //   :grad{presetOrColors}This part is gradient:
                    // Notes:
                    //   - The gradient ends at the next unescaped ':'
                    //   - Inside the gradient text, you can escape ':' as \: \:
                    if (raw.startsWith(":grad[", i)) {
                        int braceEnd = raw.indexOf(']', i + 6);
                        if (braceEnd > i) {
                            String spec = raw.substring(i + 6, braceEnd);

                            StringBuilder gbuf = new StringBuilder();
                            int j = braceEnd + 1;
                            while (j < len) {
                                char cj = raw.charAt(j);
                                if (cj == '\\' && j + 1 < len) {
                                    // Allow escaping ':' and '\' inside the gradient block
                                    gbuf.append(raw.charAt(j + 1));
                                    j += 2;
                                    continue;
                                }
                                if (cj == ':') break;
                                gbuf.append(cj);
                                j++;
                            }

                            if (j < len && raw.charAt(j) == ':') {
                                // flush text
                                if (buf.length() > 0) {
                                    out.add(new TextPart(buf.toString(), hex));
                                    buf.setLength(0);
                                }
                                out.add(new GradientPart(gbuf.toString(), spec));
                                i = j + 1;
                                continue;
                            }
                        }
                    }

                    Part iconPart = null;
                    int close = -1;
                    int typedCloseCandidate = -1;

                    // Typed tokens like :#ITEM:minecraft:diamond#size@5: contain internal ':' (namespace),
                    // so we must NOT stop at the first ':' after '#ITEM'.
                    if (raw.startsWith(":#", i)) {
                        int maxJ = Math.min(len - 1, i + 128);
                        for (int j = maxJ; j > i + 1; j--) {
                            if (raw.charAt(j) != ':') continue;
                            if (typedCloseCandidate == -1) typedCloseCandidate = j;
                            String token = raw.substring(i + 1, j);
                            iconPart = safeResolveTokenToPart(token);
                            if (iconPart != null) {
                                close = j;
                                break;
                            }
                        }
                    } else {
                        // Legacy tokens (:diamond: or :minecraft:diamond:)
                        int maxJ = Math.min(len - 1, i + 64);
                        for (int j = i + 1; j <= maxJ; j++) {
                            if (raw.charAt(j) != ':') continue;
                            String token = raw.substring(i + 1, j);
                            iconPart = safeResolveTokenToPart(token);
                            if (iconPart != null) {
                                close = j;
                                break;
                            }
                        }
                    }

                                        // If this looks like a typed token (:#ITEM:...:) but couldn't be resolved (e.g. empty hand),
                    // consume it silently instead of printing the raw token text.
                    if (iconPart == null && close == -1 && raw.startsWith(":#", i) && typedCloseCandidate != -1) {
                        String tok = raw.substring(i + 1, typedCloseCandidate).trim().toLowerCase(Locale.ROOT);
                        if (tok.startsWith("#")) tok = tok.substring(1);
                        if (tok.startsWith("item:") || tok.startsWith("block:") || tok.startsWith("entity:") || tok.startsWith("mob:")) {
                            i = typedCloseCandidate + 1;
                            continue;
                        }
                    }

if (iconPart != null && close != -1) {
                        // flush text
                        if (buf.length() > 0) {
                            out.add(new TextPart(buf.toString(), hex));
                            buf.setLength(0);
                        }
                        out.add(iconPart);
                        i = close + 1;
                        continue;
                    }
                }

                buf.append(c);
                i++;
            }

            if (buf.length() > 0) {
                out.add(new TextPart(buf.toString(), hex));
            }

            return new RichText(out);
        }

        public int measureWidth(Font font, int iconPx) {
            int w = 0;
            for (Part p : parts) {
                w += p.width(font, iconPx);
            }
            return w;
        }

        public void render(PoseStack pose, MultiBufferSource buffers, Font font,
                           float x, float y, int color, int iconPx, float brightness) {
            float cx = x;
            for (Part p : parts) {
                p.render(pose, buffers, font, cx, y, color, iconPx, brightness);
                cx += p.width(font, iconPx);
            }
        }

        private interface Part {
            int width(Font font, int iconPx);
            void render(PoseStack pose, MultiBufferSource buffers, Font font, float x, float y, int color, int iconPx, float brightness);
        }
private static Part safeResolveTokenToPart(String tokenRaw) {
    try {
        return resolveTokenToPart(tokenRaw);
    } catch (Exception ignored) {
        return null;
    }
}

private static Part resolveTokenToPart(String tokenRaw) {
    if (tokenRaw == null) return null;
    String token = tokenRaw.trim();
    if (token.isEmpty()) return null;

    // Allow a bit of sloppy input like [minecraft:stone] without breaking
    // our option syntax ...[size@...,...]
    if (token.startsWith("[") && token.endsWith("]") && token.indexOf('[') == 0 && token.lastIndexOf(']') == token.length() - 1) {
        token = token.substring(1, token.length() - 1).trim();
    }
    if (token.isEmpty()) return null;
    token = token.toLowerCase(Locale.ROOT);

    // New typed token syntax (keeps old :id: behavior too):
    //   :#ITEM:minecraft:emerald[size@0.8,rotate@false,facing@east]:
    //   :#BLOCK:minecraft:stone[size@0.6,rotate@true]:
    //   :#ENTITY:minecraft:zombie[size@1.0,rotate@false,facing@north]:
    // Legacy typed size still supported:
    //   :#ITEM:minecraft:emerald#size@2:
    // Notes:
    //   - size@N is a multiplier on the default icon size for that type
    //   - rotate@true/false controls spin (entities) or model rotation (blocks)
    //   - facing@north/east/south/west is used when rotate@false
    //   - ITEM/BLOCK/ENTITY are case-insensitive; leading '#' is optional
    TokenSpec spec = TokenSpec.tryParse(token);
    if (spec != null) {
        float mult = spec.sizeMult;
        switch (spec.kind) {
            case ITEM -> {
                ItemStack item = resolveItemIcon(spec.id);
                if (item == null) item = resolveBlockIcon(spec.id);
                if (item != null) return new IconPart(item, mult * DEFAULT_ITEM_MULT);
            }
            case BLOCK -> {
                // For explicit BLOCK tokens, render a true 3D block model (not an item icon).
                Block block = resolveBlock(spec.id);
                if (block != null) return new BlockPart(block.defaultBlockState(), mult * DEFAULT_BLOCK_MULT, spec.pose);
            }
            case ENTITY -> {
                EntityType<?> type = resolveEntityType(spec.id);
                if (type != null) return new EntityPart(type, spec.id, mult * DEFAULT_ENTITY_MULT, spec.pose);
            }
        }
        // If the typed token can't be resolved, fall through to legacy parsing.
    }

    // Legacy auto-detect:
    // 1) Items (includes most blocks, since blocks usually have a corresponding BlockItem)
    ItemStack item = resolveItemIcon(token);
    if (item != null) return new IconPart(item, DEFAULT_ITEM_MULT);

    // 2) Blocks without an item form
    ItemStack blockItem = resolveBlockIcon(token);
    if (blockItem != null) return new IconPart(blockItem, DEFAULT_ITEM_MULT);

    // 3) Entities (render the model directly)
    EntityType<?> type = resolveEntityType(token);
    if (type != null) return new EntityPart(type, token, DEFAULT_ENTITY_MULT, IconPose.entityDefault());

    return null;
}

private enum TokenKind { ITEM, BLOCK, ENTITY }

/** Spin axis for 3D icons: around the vertical axis (turntable) or the horizontal one (flip). */
private enum Spin {
    NONE, HORIZONTAL, VERTICAL;

    static Spin parse(String raw, Spin fallback) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "yes", "1", "on", "h", "horizontal", "y" -> HORIZONTAL;
            case "v", "vertical", "x" -> VERTICAL;
            case "false", "no", "0", "off", "none" -> NONE;
            default -> fallback;
        };
    }
}

/**
 * How a 3D icon is posed. Options inside the token's [...]:
 *   rotate@h|v|false   spin horizontally / vertically / not at all (rotate@true = h)
 *   speed@80           spin speed in degrees per second
 *   facing@north|...   fixed facing (same as yaw@)
 *   yaw@30  pitch@20   fixed angles in degrees
 *   animate@false      entities only: freeze the model's idle animation
 */
private record IconPose(Spin spin, float speed, Float yaw, float pitch, boolean animate) {
    /** Smooth real-time angle; the game clock gets corrected by the server and would make spins jump. */
    float spinAngle() {
        return (float) ((net.minecraft.Util.getMillis() / 1000.0 * speed) % 360.0);
    }

    static IconPose entityDefault() { return new IconPose(Spin.HORIZONTAL, 80f, null, 0f, true); }
}

private enum FacingDir {
    NORTH, EAST, SOUTH, WEST;

    static FacingDir parse(String raw) {
        if (raw == null) return NORTH;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "north" -> NORTH;
            case "east" -> EAST;
            case "south" -> SOUTH;
            case "west" -> WEST;
            default -> NORTH;
        };
    }

    /** Vanilla yaw convention: 0=south, 90=west, -90=east, 180=north. */
    float yawDegrees() {
        return switch (this) {
            case SOUTH -> 0.0f;
            case WEST -> 90.0f;
            case EAST -> -90.0f;
            case NORTH -> 180.0f;
        };
    }
}

private static final class TokenSpec {
    final TokenKind kind;
    final String id;
    final float sizeMult;
    final IconPose pose;

    private TokenSpec(TokenKind kind, String id, float sizeMult, IconPose pose) {
        this.kind = kind;
        this.id = id;
        this.sizeMult = sizeMult;
        this.pose = pose;
    }

    static TokenSpec tryParse(String tokenLower) {
        if (tokenLower == null || tokenLower.isEmpty()) return null;

        String t = tokenLower;
        if (t.startsWith("#")) t = t.substring(1);

        TokenKind kind;
        if (t.startsWith("item:")) {
            kind = TokenKind.ITEM;
            t = t.substring("item:".length());
        } else if (t.startsWith("block:")) {
            kind = TokenKind.BLOCK;
            t = t.substring("block:".length());
        } else if (t.startsWith("entity:") || t.startsWith("mob:")) {
            kind = TokenKind.ENTITY;
            t = t.substring(t.startsWith("mob:") ? "mob:".length() : "entity:".length());
        } else {
            return null;
        }

        t = t.trim();
        if (t.isEmpty()) return null;

        // Defaults:
        // - Entities spin by default (turntable) for a "showcase" feel.
        // - Blocks show as placed (upright, front face to the viewer) unless rotation is requested.
        Spin spin = kind == TokenKind.ENTITY ? Spin.HORIZONTAL : Spin.NONE;
        float speed = 80f;
        Float yaw = null;
        float pitch = 0f;
        boolean animate = true;
        float mult = 1.0f; // option multiplier over the DEFAULT_*_MULT of that token type

        // Support both syntaxes:
        //   :#ITEM:...#size@2:  (legacy)
        //   :#ITEM:...[size@0.6,rotate@false,facing@east]: (new)
        String idPart = t;
        String optPart = null;

        int b0 = t.indexOf('[');
        int b1 = (b0 >= 0) ? t.lastIndexOf(']') : -1;
        if (b0 >= 0 && b1 > b0) {
            idPart = t.substring(0, b0).trim();
            optPart = t.substring(b0 + 1, b1).trim();
        }

        // Legacy #size@ on the id portion
        int legacySize = idPart.indexOf("#size@");
        if (legacySize >= 0) {
            String before = idPart.substring(0, legacySize).trim();
            String num = idPart.substring(legacySize + "#size@".length()).trim();
            idPart = before;
            if (!num.isEmpty()) {
                try { mult = Float.parseFloat(num); } catch (Exception ignored) { mult = 1.0f; }
            }
        }

        // New options
        if (optPart != null && !optPart.isEmpty()) {
            String[] opts = optPart.split(",");
            for (String o : opts) {
                if (o == null) continue;
                String s = o.trim();
                if (s.isEmpty()) continue;

                int at = s.indexOf('@');
                if (at <= 0 || at >= s.length() - 1) continue;
                String key = s.substring(0, at).trim().toLowerCase(Locale.ROOT);
                String val = s.substring(at + 1).trim();
                // tolerate typos like "false/false" by taking the first segment
                int slash = val.indexOf('/');
                if (slash > 0) val = val.substring(0, slash).trim();

                switch (key) {
                    case "size" -> {
                        try { mult = Float.parseFloat(val); } catch (Exception ignored) { /* keep */ }
                    }
                    case "rotate", "spin" -> spin = Spin.parse(val, spin);
                    case "speed" -> { try { speed = clamp(Float.parseFloat(val), -720f, 720f); } catch (Exception ignored) {} }
                    // Entities keep their established 3/4 view (+30 degrees) for named facings.
                    case "facing" -> { yaw = FacingDir.parse(val).yawDegrees() + (kind == TokenKind.ENTITY ? 30f : 0f); spin = Spin.NONE; }
                    case "yaw" -> { try { yaw = Float.parseFloat(val); } catch (Exception ignored) {} }
                    case "pitch" -> { try { pitch = Float.parseFloat(val); } catch (Exception ignored) {} }
                    case "animate" -> animate = !(val.equalsIgnoreCase("false") || val.equals("0") || val.equalsIgnoreCase("no"));
                }
            }
        }

        if (idPart.isEmpty()) return null;
        mult = clamp(mult, 0.01f, 4.0f);
        return new TokenSpec(kind, idPart, mult, new IconPose(spin, speed, yaw, pitch, animate));
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

private static final class TextPart implements Part {
            private final String text;
            /** Hex colour from §#RRGGBB (0 = use the widget colour). */
            private final int hex;
            private TextPart(String text) { this(text, 0); }
            private TextPart(String text, int hex) { this.text = text; this.hex = hex; }

            @Override
            public int width(Font font, int iconPx) {
                return font.width(text);
            }

            @Override
            public void render(PoseStack pose, MultiBufferSource buffers, Font font, float x, float y, int color, int iconPx, float brightness) {
                if (text.isEmpty()) return;
                int base = hex == 0 ? color : (color & 0xFF000000) | (hex & 0xFFFFFF);
                int col = applyBrightnessToArgb(base, brightness);
                font.drawInBatch(text, x, y, col, false,
                        pose.last().pose(), buffers, false,
                        0, LightTexture.FULL_BRIGHT);
            }
        }



        private static final class GradientPart implements Part {
            private final String text;
            private final int startRgb;
            private final int endRgb;
            // Visible glyphs, each prefixed with the active style codes (§l, §o, ...). Colour codes are
            // dropped (the gradient owns the colour) and §r resets styles, so "§l" no longer prints an "l".
            private final List<String> glyphs = new ArrayList<>();

            private GradientPart(String text, String spec) {
                this.text = text == null ? "" : text;
                int[] stops = resolveGradientStops(spec);
                this.startRgb = stops[0];
                this.endRgb = stops[1];

                StringBuilder styles = new StringBuilder();
                for (int i = 0; i < this.text.length(); i++) {
                    char ch = this.text.charAt(i);
                    if (ch == '§' && i + 1 < this.text.length()) {
                        char code = Character.toLowerCase(this.text.charAt(i + 1));
                        if (code == 'r') styles.setLength(0);
                        else if (code >= 'k' && code <= 'o') styles.append('§').append(code);
                        i++;
                        continue;
                    }
                    glyphs.add(styles + String.valueOf(ch));
                }
            }

            @Override
            public int width(Font font, int iconPx) {
                int w = 0;
                for (String g : glyphs) w += font.width(g);
                return w;
            }

            @Override
            public void render(PoseStack pose, MultiBufferSource buffers, Font font, float x, float y, int color, int iconPx, float brightness) {
                if (glyphs.isEmpty()) return;

                int a = mulAlpha((color >>> 24) & 0xFF);
                int n = glyphs.size();
                float cx = x;

                for (int i = 0; i < n; i++) {
                    float t = (n <= 1) ? 0.0f : (i / (float) (n - 1));
                    int rgb = lerpRgb(startRgb, endRgb, t);
                    rgb = applyBrightnessToRgb(rgb, brightness);
                    int col = (a << 24) | (rgb & 0xFFFFFF);

                    String s = glyphs.get(i);
                    font.drawInBatch(s, cx, y, col, false,
                            pose.last().pose(), buffers, false,
                            0, LightTexture.FULL_BRIGHT);
                    cx += font.width(s);
                }
            }
        }

        private static int[] resolveGradientStops(String specRaw) {
            String spec = specRaw == null ? "" : specRaw.trim();
            if (spec.isEmpty()) {
                return new int[] { 0xFF00FF, 0x00FFFF }; // default magenta -> cyan
            }

            String s = spec.toLowerCase(java.util.Locale.ROOT);

            // Presets (feel free to add more)
            switch (s) {
                case "sunset" -> { return new int[] { 0xFF5F6D, 0xFFC371 }; }
                case "aqua" -> { return new int[] { 0x00FFFF, 0x0077FF }; }
                case "lime" -> { return new int[] { 0xB6FF00, 0x00FF6A }; }
                case "pink" -> { return new int[] { 0xFF00FF, 0xFF66CC }; }
                case "fire" -> { return new int[] { 0xFF4B1F, 0xFFB347 }; }
                case "ice" -> { return new int[] { 0xB2FEFA, 0x0ED2F7 }; }
                case "purple" -> { return new int[] { 0x8E2DE2, 0x4A00E0 }; }
                case "gold" -> { return new int[] { 0xF7971E, 0xFFD200 }; }
            }

            // Custom: "#RRGGBB-#RRGGBB" or "RRGGBB-RRGGBB" or "r,g,b-r,g,b" (also "->", as tab-complete suggests)
            String[] parts = s.contains("->") ? s.split("->", 2) : s.split("-", 2);
            if (parts.length == 2) {
                Integer a = parseRgb(parts[0]);
                Integer b = parseRgb(parts[1]);
                if (a != null && b != null) {
                    return new int[] { a, b };
                }
            }

            // Fallback to default
            return new int[] { 0xFF00FF, 0x00FFFF };
        }

        private static Integer parseRgb(String raw) {
            if (raw == null) return null;
            String s = raw.trim();
            if (s.isEmpty()) return null;

            // hex
            if (s.startsWith("#")) s = s.substring(1);
            if (s.matches("[0-9a-f]{6}")) {
                try {
                    return Integer.parseInt(s, 16) & 0xFFFFFF;
                } catch (Exception ignored) {}
            }

            // rgb triplet
            String[] p = s.split(",");
            if (p.length == 3) {
                try {
                    int r = clamp255(Integer.parseInt(p[0].trim()));
                    int g = clamp255(Integer.parseInt(p[1].trim()));
                    int b = clamp255(Integer.parseInt(p[2].trim()));
                    return (r << 16) | (g << 8) | b;
                } catch (Exception ignored) {}
            }

            return null;
        }

        private static int clamp255(int v) {
            return Math.max(0, Math.min(255, v));
        }

        private static int lerpRgb(int a, int b, float t) {
            int ar = (a >> 16) & 0xFF;
            int ag = (a >> 8) & 0xFF;
            int ab = a & 0xFF;

            int br = (b >> 16) & 0xFF;
            int bg = (b >> 8) & 0xFF;
            int bb = b & 0xFF;

            int r = (int) (ar + (br - ar) * t);
            int g = (int) (ag + (bg - ag) * t);
            int bl = (int) (ab + (bb - ab) * t);

            return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (bl & 0xFF);
        }
        
private static final class IconPart implements Part {
    private final ItemStack stack;
    private final float sizeMult;

    private IconPart(ItemStack stack, float sizeMult) {
        this.stack = stack;
        this.sizeMult = sizeMult;
    }

    @Override
    public int width(Font font, int iconPx) {
        int px = Math.max(1, Math.round(iconPx * sizeMult));
        return px + ICON_GAP;
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, Font font, float x, float y, int color, int iconPx, float brightness) {
        if (stack == null || stack.isEmpty()) return;
        int px = Math.max(1, Math.round(iconPx * sizeMult));
        float iy = y - ((px - font.lineHeight) / 2.0f) - 1.0f; // center icon to text height
        renderItemInline(pose, buffers, stack, x, iy, px);
    }
}

        /** 3D block model (not an item icon). Used only for explicit :#BLOCK:...: tokens. */
        private static final class BlockPart implements Part {
            private final net.minecraft.world.level.block.state.BlockState state;
            private final float sizeMult;
            private final IconPose iconPose;

            private BlockPart(net.minecraft.world.level.block.state.BlockState state, float sizeMult, IconPose iconPose) {
                this.state = state;
                this.sizeMult = sizeMult;
                this.iconPose = iconPose;
            }

            @Override
            public int width(Font font, int iconPx) {
                int px = Math.max(1, Math.round(iconPx * sizeMult));
                return px + ICON_GAP;
            }

            @Override
            public void render(PoseStack pose, MultiBufferSource buffers, Font font, float x, float y, int color, int iconPx, float brightness) {
                if (state == null) return;
                int px = Math.max(1, Math.round(iconPx * sizeMult));
                float iy = y - ((px - font.lineHeight) / 2.0f) - 1.0f;
                renderBlockInline(pose, buffers, state, x, iy, px, iconPose);
            }
        }


    private static final class EntityPart implements Part {
        private final EntityType<?> type;
        private final String cacheKey;
        private final float sizeMult;
        private final IconPose iconPose;

        private EntityPart(EntityType<?> type, String cacheKey, float sizeMult, IconPose iconPose) {
            this.type = type;
            this.cacheKey = cacheKey;
            this.sizeMult = sizeMult;
            this.iconPose = iconPose;
        }

        @Override
        public int width(Font font, int iconPx) {
            int px = Math.max(1, Math.round(iconPx * sizeMult));
            return px + ICON_GAP;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, Font font, float x, float y, int color, int iconPx, float brightness) {
            if (type == null) return;
            int px = Math.max(1, Math.round(iconPx * sizeMult));
            float iy = y - ((px - font.lineHeight) / 2.0f) - 1.0f;
            renderEntityInline(pose, buffers, type, cacheKey, x, iy, px, iconPose);
        }
    }
}

/**
 * GUI-style item rendering inside the panel coordinate space.
 *
 * @param x,y top-left corner in panel px-space
 */
    /**
     * An item icon filling the iconPx x iconPx box whose top-left is (x, y), like the inventory draws it:
     * the GUI model spans -0.5..0.5, so it is centred in the box and scaled to its size. Y is flipped for
     * panel space and depth is kept thin so block items don't poke through the panel.
     */
    private static void renderItemInline(PoseStack pose, MultiBufferSource buffers, ItemStack stack, float x, float y, int iconPx) {
        renderItemBox(pose, buffers, stack, x, y, iconPx);
    }

    static void renderItemBox(PoseStack pose, MultiBufferSource buffers, ItemStack stack, float x, float y, float size) {
        if (stack == null || stack.isEmpty() || size <= 0) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        pose.pushPose();
        pose.translate(x + size / 2f, y + size / 2f, 0.1f);
        pose.scale(size, -size, size * 0.05f);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemTransforms.TransformType.GUI,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers, 0);
        pose.popPose();
    }

    /**
     * Renders a true 3D block model (BlockState) in panel space.
     * This is used only for explicit :#BLOCK:...: tokens.
     */
    private static void renderBlockInline(PoseStack pose, MultiBufferSource buffers,
                                          net.minecraft.world.level.block.state.BlockState state,
                                          float x, float y, int iconPx, RichText.IconPose ip) {
        if (state == null) return;

        Minecraft mc = Minecraft.getInstance();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        float spin = ip.spin() == RichText.Spin.NONE ? 0f : ip.spinAngle();
        float yaw = (ip.yaw() == null ? 0f : ip.yaw()) + (ip.spin() == RichText.Spin.HORIZONTAL ? spin : 0f);
        float pitch = ip.pitch() + (ip.spin() == RichText.Spin.VERTICAL ? spin : 0f);
        boolean axisAligned = ip.spin() == RichText.Spin.NONE && pitch % 90f == 0f && yaw % 90f == 0f;

        pose.pushPose();

        // Centre of the icon box; models rotate around their own centre.
        pose.translate(x + (iconPx / 2.0f), y + (iconPx / 2.0f), 0.0f);
        keepModelProportions(pose);

        // Undo the panel's Y-down flip so the block stands upright, as if placed.
        pose.mulPose(Vector3f.ZP.rotationDegrees(180.0f));

        float s = iconPx * 0.8f;
        // Sit on the viewer's side of the panel: the back face touches the panel when the block is square-on,
        // and a turning block keeps its corners clear of it.
        pose.translate(0.0f, 0.0f, towardViewer(pose) * s * (axisAligned ? 0.5f : 0.87f));

        pose.mulPose(Vector3f.XP.rotationDegrees(pitch));
        pose.mulPose(Vector3f.YP.rotationDegrees(yaw));
        pose.scale(s, s, s);
        pose.translate(-0.5f, -0.5f, -0.5f);

        mc.getBlockRenderer().renderSingleBlock(state, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);

        pose.popPose();
    }

    /**
     * Text widgets scale x/y but not depth (pose.scale(s, s, 1)), which is right for flat glyphs but squashes
     * 3D models. Rescale depth to match so blocks and entities keep their real shape at any text size.
     */
    private static void keepModelProportions(PoseStack pose) {
        com.mojang.math.Matrix4f m = pose.last().pose();
        com.mojang.math.Vector4f ax = new com.mojang.math.Vector4f(1, 0, 0, 0);
        com.mojang.math.Vector4f az = new com.mojang.math.Vector4f(0, 0, 1, 0);
        ax.transform(m);
        az.transform(m);
        float lx = (float) Math.sqrt(ax.x() * ax.x() + ax.y() * ax.y() + ax.z() * ax.z());
        float lz = (float) Math.sqrt(az.x() * az.x() + az.y() * az.y() + az.z() * az.z());
        if (lz > 1e-6f && Math.abs(lx / lz - 1f) > 1e-3f) pose.scale(1f, 1f, lx / lz);
    }

    /**
     * +1 if local +Z points toward the camera, else -1. Poses are camera-relative, so the direction from
     * this point to the camera is minus its own position; that works whichever side the panel is seen from.
     */
    private static float towardViewer(PoseStack pose) {
        com.mojang.math.Matrix4f m = pose.last().pose();
        com.mojang.math.Vector4f origin = new com.mojang.math.Vector4f(0, 0, 0, 1);
        com.mojang.math.Vector4f z = new com.mojang.math.Vector4f(0, 0, 1, 0);
        origin.transform(m);
        z.transform(m);
        float dot = -(origin.x() * z.x() + origin.y() * z.y() + origin.z() * z.z());
        return dot >= 0 ? 1f : -1f;
    }

    private static void renderEntityInline(PoseStack pose, MultiBufferSource buffers, EntityType<?> type, String cacheKey,
                                           float x, float y, int iconPx, RichText.IconPose ip) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || type == null) return;

        // If the client level changes (dimension switch / reconnect), drop cached entities.
        synchronized (ENTITY_INLINE_CACHE) {
            if (ENTITY_INLINE_CACHE_LEVEL != mc.level) {
                ENTITY_INLINE_CACHE.clear();
                ENTITY_INLINE_CACHE_LEVEL = mc.level;
            }
        }

        Entity entity;
        synchronized (ENTITY_INLINE_CACHE) {
            entity = ENTITY_INLINE_CACHE.get(cacheKey);
            if (entity == null || entity.getType() != type) {
                entity = type.create(mc.level);
                if (entity == null) return;
                // Keep it stable; we just need a renderable instance
                entity.setPos(0, 0, 0);
                ENTITY_INLINE_CACHE.put(cacheKey, entity);
            }
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();

        float spin = ip.spin() == RichText.Spin.NONE ? 0f : ip.spinAngle();
        // Unset yaw keeps the previous default (facing north, turned 30 degrees for a 3/4 view).
        float baseYaw = ip.yaw() == null ? 210f : ip.yaw();
        float yaw = ip.spin() == RichText.Spin.HORIZONTAL ? 30f + spin : baseYaw;
        float pitch = ip.pitch() + (ip.spin() == RichText.Spin.VERTICAL ? spin : 0f);

        // The cached instance is shared and never ticked: pin its own rotations every frame so nothing
        // left over (head turn, body yaw) can make it face a random way, and drive idle animation
        // from real time when animate is on, or freeze it.
        entity.setYRot(0f);
        entity.setXRot(0f);
        entity.yRotO = 0f;
        entity.xRotO = 0f;
        if (entity instanceof net.minecraft.world.entity.LivingEntity le) {
            le.yBodyRot = 0f; le.yBodyRotO = 0f;
            le.yHeadRot = 0f; le.yHeadRotO = 0f;
        }
        long ms = net.minecraft.Util.getMillis();
        entity.tickCount = ip.animate() ? (int) ((ms / 50L) % 1_000_000L) : 0;
        float partial = ip.animate() ? (ms % 50L) / 50f : 0f;

        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);

        pose.pushPose();

        // Centre of the icon box; the model rotates around its own centre.
        pose.translate(x + (iconPx / 2.0f), y + (iconPx / 2.0f), 0.0f);
        keepModelProportions(pose);

        // Undo the panel's Y-down flip so the model stands upright.
        pose.mulPose(Vector3f.ZP.rotationDegrees(180.0f));

        // Scale based on the entity's bounding box so different mobs (and boats, etc.) size consistently.
        float bbH = Math.max(0.1f, entity.getBbHeight());
        float bbW = Math.max(0.1f, entity.getBbWidth());
        float maxDim = Math.max(bbW, bbH);
        float s = (iconPx / maxDim) * 0.85f;

        // Keep the whole model on the viewer's side of the panel while it turns.
        pose.translate(0.0f, 0.0f, towardViewer(pose) * bbW * s * 0.75f);

        pose.mulPose(Vector3f.XP.rotationDegrees(pitch));
        pose.mulPose(Vector3f.YP.rotationDegrees(yaw));
        pose.scale(s, s, s);
        pose.translate(0.0f, -bbH / 2.0f, 0.0f); // models are drawn feet-first; centre them

        dispatcher.render(entity, 0.0, 0.0, 0.0, 0.0f, partial, pose, buffers, LightTexture.FULL_BRIGHT);

        pose.popPose();
        dispatcher.setRenderShadow(true);
    }

    private static ItemStack resolveItemIcon(String token) {
        ResourceLocation id = parseId(token);

        // 1) explicit namespace:id
        if (id != null && ForgeRegistries.ITEMS.containsKey(id)) {
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null) {
                ItemStack st = new ItemStack(item);
                if (!st.isEmpty()) return st;
            }
        }

        // 2) shorthand :iron_ore: -> try minecraft first
        if (id == null && token.indexOf(':') < 0) {
            ResourceLocation mc;
            try {
                mc = new ResourceLocation("minecraft", token);
            } catch (Exception ignored) {
                mc = null;
            }
            if (mc != null && ForgeRegistries.ITEMS.containsKey(mc)) {
                Item item = ForgeRegistries.ITEMS.getValue(mc);
                if (item != null) {
                ItemStack st = new ItemStack(item);
                if (!st.isEmpty()) return st;
            }
            }

            // 3) fallback: search any mod whose path matches
            List<ResourceLocation> matches = ForgeRegistries.ITEMS.getKeys().stream()
                    .filter(k -> k.getPath().equals(token))
                    .sorted(Comparator.comparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath))
                    .toList();
            if (!matches.isEmpty()) {
                Item item = ForgeRegistries.ITEMS.getValue(matches.get(0));
                if (item != null) {
                ItemStack st = new ItemStack(item);
                if (!st.isEmpty()) return st;
            }
            }
        }

        return null;
    }

    /** Resolve a block registry entry (even if it has no BlockItem). */
    private static Block resolveBlock(String token) {
        if (token == null) return null;
        ResourceLocation id = parseId(token);

        // 1) explicit namespace:id
        if (id != null && ForgeRegistries.BLOCKS.containsKey(id)) {
            return ForgeRegistries.BLOCKS.getValue(id);
        }

        // 2) shorthand :stone:
        if (id == null && token.indexOf(':') < 0) {
            ResourceLocation mc;
            try {
                mc = new ResourceLocation("minecraft", token);
            } catch (Exception ignored) {
                mc = null;
            }
            if (mc != null && ForgeRegistries.BLOCKS.containsKey(mc)) {
                return ForgeRegistries.BLOCKS.getValue(mc);
            }

            // 3) fallback: search any mod whose path matches
            List<ResourceLocation> matches = ForgeRegistries.BLOCKS.getKeys().stream()
                    .filter(k -> k.getPath().equals(token))
                    .sorted(Comparator.comparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath))
                    .toList();
            if (!matches.isEmpty()) {
                return ForgeRegistries.BLOCKS.getValue(matches.get(0));
            }
        }

        return null;
    }

    /**
     * Blocks can exist without a corresponding BlockItem. This provides a fallback
     * path so tokens like :modid:some_block: still render something when possible.
     */
    private static ItemStack resolveBlockIcon(String token) {
        ResourceLocation id = parseId(token);

        // 1) explicit namespace:id
        if (id != null && ForgeRegistries.BLOCKS.containsKey(id)) {
            Block b = ForgeRegistries.BLOCKS.getValue(id);
            if (b != null) {
                Item asItem = b.asItem();
                if (asItem != null && asItem != Items.AIR) {
                    return new ItemStack(asItem);
                }
            }
        }

        // 2) shorthand :stone:
        if (id == null && token.indexOf(':') < 0) {
            ResourceLocation mc;
            try {
                mc = new ResourceLocation("minecraft", token);
            } catch (Exception ignored) {
                mc = null;
            }
            if (mc != null && ForgeRegistries.BLOCKS.containsKey(mc)) {
                Block b = ForgeRegistries.BLOCKS.getValue(mc);
                if (b != null) {
                    Item asItem = b.asItem();
                    if (asItem != null && asItem != Items.AIR) {
                        return new ItemStack(asItem);
                    }
                }
            }

            // 3) fallback: search any mod whose path matches
            List<ResourceLocation> matches = ForgeRegistries.BLOCKS.getKeys().stream()
                    .filter(k -> k.getPath().equals(token))
                    .sorted(Comparator.comparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath))
                    .toList();
            if (!matches.isEmpty()) {
                Block b = ForgeRegistries.BLOCKS.getValue(matches.get(0));
                if (b != null) {
                    Item asItem = b.asItem();
                    if (asItem != null && asItem != Items.AIR) {
                        return new ItemStack(asItem);
                    }
                }
            }
        }

        return null;
    }

    private static EntityType<?> resolveEntityType(String token) {
        ResourceLocation id = parseId(token);
        if (id != null && ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            return ForgeRegistries.ENTITY_TYPES.getValue(id);
        }

        // shorthand
        if (id == null && token.indexOf(':') < 0) {
            ResourceLocation mc;
            try {
                mc = new ResourceLocation("minecraft", token);
            } catch (Exception ignored) {
                mc = null;
            }
            if (mc != null && ForgeRegistries.ENTITY_TYPES.containsKey(mc)) {
                return ForgeRegistries.ENTITY_TYPES.getValue(mc);
            }

            List<ResourceLocation> matches = ForgeRegistries.ENTITY_TYPES.getKeys().stream()
                    .filter(k -> k.getPath().equals(token))
                    .sorted(Comparator.comparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath))
                    .toList();
            if (!matches.isEmpty()) {
                return ForgeRegistries.ENTITY_TYPES.getValue(matches.get(0));
            }
        }
        return null;
    }


    private static ResourceLocation parseId(String token) {
        if (token == null) return null;
        if (token.indexOf(':') < 0) return null;
        try {
            return new ResourceLocation(token);
        } catch (Exception ignored) {
            return null;
        }
    }
}