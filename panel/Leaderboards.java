package com.luminacity.advancedinteractions.panel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Builds a ready-made top-N board from ordinary widgets (title, ranked rows, the viewer's own rank),
 * driven by {top.*} placeholders, so it stays fully editable afterwards.
 */
public final class Leaderboards {
    private Leaderboards() {}

    /**
     * @param objective scoreboard objective, or "state.KEY" to rank by a player variable
     * @param newId     gives each widget a unique id from a prefix ("lb_title" -> "lb_title1")
     */
    public static List<PanelWidget> build(String objective, int rows, double x, double y, double width,
                                          String title, Function<String, String> newId) {
        List<PanelWidget> out = new ArrayList<>();
        ShapeWidget bg = new ShapeWidget(newId.apply("lb_bg"), ShapeWidget.Type.RRECT);
        bg.setPos(x - 4, y - 4);
        bg.setSize(width + 8, 18 + rows * 11 + 14);
        bg.setFill(0x88000000);
        bg.setCornerRadius(4);
        bg.setLayer(-1);
        out.add(bg);

        TextWidget head = new TextWidget(newId.apply("lb_title"), x + width / 2, y,
                "§6§l" + (title == null || title.isBlank() ? "Top " + rows : title));
        head.setAlign("center");
        out.add(head);

        String[] medal = { "§6", "§7", "§c" };
        for (int i = 1; i <= rows; i++) {
            double ry = y + 14 + (i - 1) * 11;
            String c = i <= 3 ? medal[i - 1] : "§8";
            TextWidget name = new TextWidget(newId.apply("lb_name"), x, ry,
                    c + "#" + i + " §f{top.name:" + objective + ":" + i + "|default:-}");
            out.add(name);
            TextWidget value = new TextWidget(newId.apply("lb_value"), x + width, ry,
                    "§e{top.value:" + objective + ":" + i + "|number}");
            value.setAlign("right");
            value.setCondition("has(top.name:" + objective + ":" + i + ")");
            out.add(value);
        }
        TextWidget you = new TextWidget(newId.apply("lb_you"), x + width / 2, y + 16 + rows * 11,
                "§7You: §f#{top.rank:" + objective + "|default:-}");
        you.setAlign("center");
        you.setScale(0.75f);
        out.add(you);
        return out;
    }
}
