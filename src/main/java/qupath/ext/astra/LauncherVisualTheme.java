package qupath.ext.astra;

import java.util.Locale;

enum LauncherVisualTheme {
    MODERN("Modern"),
    SOFT("Soft"),
    SLATE("Slate");

    private final String label;

    LauncherVisualTheme(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    String styleClass() {
        return "astra-theme-" + name().toLowerCase(Locale.ROOT);
    }

    static LauncherVisualTheme fromText(String raw) {
        if (raw == null || raw.isBlank()) {
            return MODERN;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return MODERN;
        }
    }
}
