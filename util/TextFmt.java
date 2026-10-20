package com.luminacity.advancedinteractions.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Colour codes typed with &amp; (the § sign isn't on most keyboards): &amp;0-&amp;f, &amp;k-&amp;o, &amp;r and hex
 * colours &amp;#RRGGBB. Converted to § codes (§#RRGGBB for hex, which the hologram renderer understands).
 */
public class TextFmt {
    private static final java.util.regex.Pattern AMP_CODES =
            java.util.regex.Pattern.compile("(?i)&(#[0-9a-f]{6}|[0-9a-fk-or])");

    /** Convert &0-&f, &k-&o, &r and &#RRGGBB to § codes. */
    public static String mc(String in) {
        if (in == null || in.isEmpty() || in.indexOf('&') < 0) return in;
        return AMP_CODES.matcher(in).replaceAll("\u00A7$1");
    }

    /** Six hex digits starting at {@code from}? */
    public static boolean isHex(String s, int from) {
        if (from < 0 || from + 6 > s.length()) return false;
        for (int i = from; i < from + 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    /** Text with & / § codes (including hex colours) as a chat component, e.g. for tooltips and messages. */
    public static MutableComponent component(String in) {
        String s = mc(in == null ? "" : in);
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00A7' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                Style next = null;
                int skip = 1;
                if (code == '#' && isHex(s, i + 2)) {
                    next = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(s.substring(i + 2, i + 8), 16)));
                    skip = 7;
                } else {
                    ChatFormatting f = ChatFormatting.getByCode(code);
                    if (f != null) next = f == ChatFormatting.RESET ? Style.EMPTY : f.isColor() ? Style.EMPTY.withColor(f) : style.applyFormat(f);
                }
                if (next != null) {
                    if (buf.length() > 0) { out.append(Component.literal(buf.toString()).withStyle(style)); buf.setLength(0); }
                    style = next;
                    i += skip;
                    continue;
                }
            }
            buf.append(c);
        }
        if (buf.length() > 0) out.append(Component.literal(buf.toString()).withStyle(style));
        return out;
    }
}
