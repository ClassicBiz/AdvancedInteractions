package com.luminacity.advancedinteractions.panel;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What happens when an element is clicked: actions (and action chains), click types, cooldown, cost,
 * who may use it, confirmation, tooltip and feedback. Any element can carry these; buttons always do.
 * Saved with the element and synced to clients, which play feedback instantly; the server enforces
 * everything else.
 */
public final class ClickSettings {

    /** How the element was clicked. Right-click and the interact key are PRIMARY. */
    public enum Type {
        PRIMARY, LEFT, SHIFT;

        public static Type byId(int id) {
            return id == 1 ? LEFT : id == 2 ? SHIFT : PRIMARY;
        }

        public int id() { return ordinal(); }
    }

    /** One step of an action chain: runs after the previous step depending on its result. */
    public static final class Step {
        public enum When { ALWAYS, SUCCESS, FAILURE }

        public String action = "";
        public When when = When.SUCCESS;
        /** Seconds to wait before this step. */
        public float delay = 0f;

        public Step() {}
        public Step(String action, When when, float delay) { this.action = action; this.when = when; this.delay = delay; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("a", action);
            if (when != When.SUCCESS) t.putString("when", when.name().toLowerCase(Locale.ROOT));
            if (delay > 0) t.putFloat("delay", delay);
            return t;
        }

        static Step load(CompoundTag t) {
            Step s = new Step();
            s.action = t.getString("a");
            try { if (t.contains("when")) s.when = When.valueOf(t.getString("when").toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
            s.delay = t.getFloat("delay");
            return s;
        }
    }

    /** What a player who may not use the element sees. */
    public enum Unusable { HIDE, DISABLE }

    public static final String SOUND_DEFAULT = "minecraft:ui.button.click";
    public static final String SOUND_OFF = "off";

    // ---- clickability (non-button elements; buttons are always clickable and keep their own action)
    public boolean clickable = false;
    public String action = "";
    /** Brighten / grow slightly under the crosshair so clickable elements stand out. */
    public boolean hoverEffect = true;

    // ---- actions
    public String leftAction = "";
    public String shiftAction = "";
    /** Extra steps after the main action (action chain). */
    public List<Step> chain = new ArrayList<>();

    // ---- limits
    public int cooldownTicks = 0;
    /** True: one cooldown shared by everyone. False: each player has their own. */
    public boolean cooldownGlobal = false;
    public String costItem = "";
    public int costCount = 0;
    public double costMoney = 0;

    // ---- who may use it
    public String usePerm = "";
    public int useOp = -1;
    /** Condition (same syntax as "show only if") the player must meet to use it. */
    public String useCondition = "";
    /** Shown greyed out and unclickable while this condition is true (e.g. out of stock). */
    public String disabledIf = "";
    public Unusable unusable = Unusable.DISABLE;

    // ---- extras
    /** "Are you sure?" text before running (placeholders allowed); empty = no confirmation. */
    public String confirm = "";
    /** Text shown under the crosshair while hovering (placeholders allowed). */
    public String tooltip = "";

    // ---- feedback
    /** "" = the default click sound, "off" = silent, otherwise a sound id. */
    public String sound = "";
    public float soundVolume = 0.5f;
    public float soundPitch = 1.0f;
    public String hoverSound = "";
    public String successSound = "";
    public String errorSound = "";
    /** "" = no particles, otherwise a particle id (simple particle types only). */
    public String particle = "";
    public int particleCount = 6;
    public boolean pressAnimation = true;

    /** The action for a click type: left needs its own action; shift falls back to the primary one. */
    public String actionFor(Type type, String primary) {
        return switch (type) {
            case LEFT -> leftAction;
            case SHIFT -> shiftAction.isBlank() ? primary : shiftAction;
            default -> primary;
        };
    }

    public boolean hasCost() {
        return (costCount > 0 && !costItem.isBlank()) || costMoney > 0;
    }

    public boolean hasUseRule() {
        return !usePerm.isBlank() || useOp >= 0 || !useCondition.isBlank();
    }

    public String soundId() {
        if (sound == null || sound.isBlank()) return SOUND_DEFAULT;
        return sound.equalsIgnoreCase(SOUND_OFF) ? null : sound;
    }

    // ---------------------------------------------------------------- element helpers

    /** Can this element be clicked at all? */
    public static boolean isClickable(PanelWidget w) {
        if (w instanceof ButtonWidget || w instanceof InputWidget) return true;
        ClickSettings c = w == null ? null : w.interaction();
        return c != null && c.clickable;
    }

    /** The main action of an element (a button's own action, or the click settings' action). */
    public static String primaryAction(PanelWidget w) {
        if (w instanceof ButtonWidget bw) return bw.actionId();
        ClickSettings c = w == null ? null : w.interaction();
        return c == null ? "" : c.action;
    }

    // ---------------------------------------------------------------- NBT

    public void saveTo(CompoundTag t) {
        if (clickable) t.putBoolean("clickable", true);
        if (!action.isBlank()) t.putString("action", action);
        if (!hoverEffect) t.putBoolean("noHover", true);
        if (!leftAction.isBlank()) t.putString("left", leftAction);
        if (!shiftAction.isBlank()) t.putString("shift", shiftAction);
        if (!chain.isEmpty()) {
            ListTag l = new ListTag();
            for (Step s : chain) l.add(s.save());
            t.put("chain", l);
        }
        if (cooldownTicks > 0) t.putInt("cd", cooldownTicks);
        if (cooldownGlobal) t.putBoolean("cdGlobal", true);
        if (!costItem.isBlank() && costCount > 0) { t.putString("costItem", costItem); t.putInt("costCount", costCount); }
        if (costMoney > 0) t.putDouble("costMoney", costMoney);
        if (!usePerm.isBlank()) t.putString("usePerm", usePerm);
        if (useOp >= 0) t.putInt("useOp", useOp);
        if (!useCondition.isBlank()) t.putString("useIf", useCondition);
        if (!disabledIf.isBlank()) t.putString("disabledIf", disabledIf);
        if (unusable != Unusable.DISABLE) t.putString("unusable", unusable.name().toLowerCase(Locale.ROOT));
        if (!confirm.isBlank()) t.putString("confirm", confirm);
        if (!tooltip.isBlank()) t.putString("tooltip", tooltip);
        if (!sound.isBlank()) t.putString("sound", sound);
        if (soundVolume != 0.5f) t.putFloat("soundVol", soundVolume);
        if (soundPitch != 1.0f) t.putFloat("soundPitch", soundPitch);
        if (!hoverSound.isBlank()) t.putString("hoverSound", hoverSound);
        if (!successSound.isBlank()) t.putString("okSound", successSound);
        if (!errorSound.isBlank()) t.putString("errSound", errorSound);
        if (!particle.isBlank()) { t.putString("particle", particle); t.putInt("particleCount", particleCount); }
        if (!pressAnimation) t.putBoolean("noPress", true);
    }

    public static ClickSettings load(CompoundTag t) {
        ClickSettings c = new ClickSettings();
        if (t == null) return c;
        c.clickable = t.getBoolean("clickable");
        c.action = t.getString("action");
        c.hoverEffect = !t.getBoolean("noHover");
        c.leftAction = t.getString("left");
        c.shiftAction = t.getString("shift");
        ListTag l = t.getList("chain", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) c.chain.add(Step.load(l.getCompound(i)));
        c.cooldownTicks = t.getInt("cd");
        c.cooldownGlobal = t.getBoolean("cdGlobal");
        c.costItem = t.getString("costItem");
        c.costCount = t.getInt("costCount");
        c.costMoney = t.getDouble("costMoney");
        c.usePerm = t.getString("usePerm");
        c.useOp = t.contains("useOp") ? t.getInt("useOp") : -1;
        c.useCondition = t.getString("useIf");
        c.disabledIf = t.getString("disabledIf");
        c.unusable = "hide".equalsIgnoreCase(t.getString("unusable")) ? Unusable.HIDE : Unusable.DISABLE;
        c.confirm = t.getString("confirm");
        c.tooltip = t.getString("tooltip");
        c.sound = t.getString("sound");
        if (t.contains("soundVol")) c.soundVolume = t.getFloat("soundVol");
        if (t.contains("soundPitch")) c.soundPitch = t.getFloat("soundPitch");
        c.hoverSound = t.getString("hoverSound");
        c.successSound = t.getString("okSound");
        c.errorSound = t.getString("errSound");
        c.particle = t.getString("particle");
        if (t.contains("particleCount")) c.particleCount = t.getInt("particleCount");
        c.pressAnimation = !t.getBoolean("noPress");
        return c;
    }

    public boolean isDefault() {
        CompoundTag t = new CompoundTag();
        saveTo(t);
        return t.isEmpty();
    }

    /** One line per configured setting, for /aholo ... button edit <id> info. */
    public String describe(String primary) {
        StringBuilder sb = new StringBuilder();
        sb.append("right-click: ").append(primary == null || primary.isBlank() ? "(none)" : primary);
        for (Step s : chain) {
            sb.append("\n  then (").append(s.when.name().toLowerCase(Locale.ROOT))
              .append(s.delay > 0 ? String.format(Locale.ROOT, ", after %.1fs", s.delay) : "").append("): ").append(s.action);
        }
        sb.append("\nleft-click: ").append(leftAction.isBlank() ? "(none - left-click passes through)" : leftAction);
        sb.append("\nshift-click: ").append(shiftAction.isBlank() ? "(same as right-click)" : shiftAction);
        sb.append("\ncooldown: ").append(cooldownTicks <= 0 ? "none"
                : String.format(Locale.ROOT, "%.1fs %s", cooldownTicks / 20.0, cooldownGlobal ? "(shared by everyone)" : "(per player)"));
        sb.append("\ncost: ");
        if (!hasCost()) sb.append("free");
        else {
            if (costCount > 0 && !costItem.isBlank()) sb.append(costCount).append("x ").append(costItem);
            if (costMoney > 0) sb.append(costCount > 0 ? " + " : "").append(String.format(Locale.ROOT, "%.2f money", costMoney));
        }
        if (hasUseRule()) sb.append("\nusable by: ").append(usePerm.isBlank() ? "" : "perm " + usePerm + " ")
                .append(useOp >= 0 ? "op " + useOp + " " : "").append(useCondition.isBlank() ? "" : "if " + useCondition)
                .append(unusable == Unusable.HIDE ? " (hidden otherwise)" : " (greyed out otherwise)");
        if (!disabledIf.isBlank()) sb.append("\ndisabled when: ").append(disabledIf);
        if (!confirm.isBlank()) sb.append("\nconfirm: ").append(confirm);
        if (!tooltip.isBlank()) sb.append("\ntooltip: ").append(tooltip);
        sb.append("\nsound: ").append(soundId() == null ? "off" : soundId())
          .append(String.format(Locale.ROOT, " (volume %.2f, pitch %.2f)", soundVolume, soundPitch));
        sb.append("\nparticles: ").append(particle.isBlank() ? "none" : particleCount + "x " + particle);
        sb.append("\npress animation: ").append(pressAnimation ? "on" : "off");
        return sb.toString();
    }
}
