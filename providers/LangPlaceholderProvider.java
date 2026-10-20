package com.luminacity.advancedinteractions.providers;

import com.luminacity.advancedinteractions.api.Placeholders;
import net.minecraft.locale.Language;

import java.util.Map;

/**
 * Built-in {lang.*}: a translation key shown in each viewer's own game language.
 *
 * <p>Resolved on the client, where {@link Language} is the player's selected language, so two players
 * reading the same panel can see different text. Translations come from resource packs (a server
 * resource pack works). Example: {lang.shop.welcome}, or {lang.shop.sale:20} for "%s%% off".</p>
 */
public final class LangPlaceholderProvider implements Placeholders.PlaceholderProvider {

    @Override public boolean isClientSide(String key) { return true; }

    @Override public Map<String, String> keys() {
        return Map.of("<translation.key>", "Text from a lang file in the viewer's language; "
                + "{lang.key:a,b} fills %s placeholders. Missing keys show the key itself.");
    }

    @Override
    public String resolve(String key, String args, Placeholders.PHContext ctx) {
        if (key == null || key.isBlank()) return "";
        String pattern = Language.getInstance().getOrDefault(key);
        if (args == null || args.isBlank()) return pattern;
        try {
            return String.format(pattern, (Object[]) args.split(","));
        } catch (RuntimeException e) {
            return pattern;
        }
    }
}
