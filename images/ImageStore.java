package com.luminacity.advancedinteractions.images;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server images: PNG files in config/advancedinteractions/images/, sent to clients on request so image
 * widgets can show logos, maps and portraits without a resource pack.
 */
public final class ImageStore {
    private ImageStore() {}

    /** Stay under the 1 MiB server-to-client packet limit. */
    public static final int MAX_BYTES = 900 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-z0-9_\\-.]{1,64}");

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve("advancedinteractions").resolve("images");
    }

    /** "Logo" / "logo.png" -> "logo.png"; null if the name isn't allowed. */
    public static String normalize(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (!n.endsWith(".png")) n = n + ".png";
        return NAME.matcher(n).matches() && !n.contains("..") ? n : null;
    }

    /** @return the PNG bytes, or null if missing, too large or not a PNG */
    public static byte[] read(String name) {
        String n = normalize(name);
        if (n == null) return null;
        try {
            Path p = dir().resolve(n);
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_BYTES) return null;
            byte[] data = Files.readAllBytes(p);
            boolean png = data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
            return png ? data : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(dir());
            try (var s = Files.list(dir())) {
                s.map(p -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                        .filter(n -> n.endsWith(".png") && normalize(n) != null)
                        .sorted().forEach(out::add);
            }
        } catch (Exception ignored) {}
        return out;
    }
}
