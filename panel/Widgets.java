package com.luminacity.advancedinteractions.panel;

/** Creates an empty widget for a saved "kind" (load it with {@link PanelWidget#loadFrom}). */
public final class Widgets {
    private Widgets() {}

    /** @return a new widget, or null for an unknown kind */
    public static PanelWidget create(String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case "text" -> new TextWidget();
            case "button" -> new ButtonWidget();
            case "shape" -> new ShapeWidget();
            case "image" -> new ImageWidget();
            case "chart" -> new ChartWidget();
            case "input" -> new InputWidget();
            default -> null;
        };
    }
}
