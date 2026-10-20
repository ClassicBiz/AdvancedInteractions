package com.luminacity.advancedinteractions.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits action parameters ("text=&aHi&volume=1"). An '&amp;' only separates parameters when a
 * {@code key=} follows it, so &amp; colour codes (&amp;a, &amp;#FF8800) can be used inside values.
 */
public final class QueryParams {
    private QueryParams() {}

    public static String[] split(String qs) {
        if (qs == null || qs.isEmpty()) return new String[0];
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < qs.length(); i++) {
            if (qs.charAt(i) == '&' && startsKey(qs, i + 1)) {
                out.add(qs.substring(start, i));
                start = i + 1;
            }
        }
        out.add(qs.substring(start));
        return out.toArray(new String[0]);
    }

    /** Does a "key=" (letters, digits, _ . -) start at {@code i}? */
    private static boolean startsKey(String s, int i) {
        int j = i;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '=') return j > i;
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-')) return false;
            j++;
        }
        return false;
    }
}
