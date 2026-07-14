package qupath.ext.astra;

import java.util.List;
import java.util.Set;

import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Paint;
import javafx.scene.paint.Stop;

final class LauncherThemeTokens {

    static final String INK = "#172431";
    static final String MUTED = "#5f7080";
    static final String PAPER = "#f4f7f8";
    static final String PANEL = "#ffffff";
    static final String TEAL = "#1f7a7a";
    static final String TEAL_DARK = "#0d4f55";
    static final String CORAL = "#d9604c";
    static final String GOLD = "#d4a72c";
    static final String CONTROL_BORDER = "#7fa3ad";
    static final String CHANNEL_SWATCH_STROKE = "#31404a";
    static final String CHANNEL_FALLBACK = "#b7c0c7";
    static final String FIELD_BACKGROUND = "#fbfdff";
    static final String CHANGED_VALUE_SHADOW = "rgba(212, 167, 44, 0.42)";
    private static final Color TAB_SHEEN_HIGHLIGHT = Color.rgb(255, 255, 255, 0.18d);
    private static final Color TAB_SHEEN_BODY = Color.rgb(255, 255, 255, 0.11d);
    private static final Color TAB_SHEEN_TAIL = TAB_SHEEN_BODY;

    static final double ENABLED_OPACITY = 1.0d;
    static final double HEADER_MOTION_ROW_DISABLED_OPACITY = 0.48d;
    static final String CSS_OPACITY_HIDDEN = "0";
    static final String CSS_OPACITY_SUBTLE = "0.18";
    static final String CSS_OPACITY_MUTED = "0.32";
    static final String CSS_OPACITY_LOW_GRADIENT = "0.36";
    static final String CSS_OPACITY_FOCUSED_GRADIENT = "0.42";
    static final String CSS_OPACITY_SUCCESS_GRADIENT = "0.54";
    static final String CSS_OPACITY_SETTING_ACCENT = "0.62";
    static final String CSS_OPACITY_SEPARATOR = "0.72";
    static final String CSS_OPACITY_SHIMMER = "0.78";
    static final String CSS_OPACITY_PARAMETER_ACCENT = "0.82";
    static final String CSS_OPACITY_HEADER_DISABLED = "0.84";
    static final String CSS_OPACITY_FOOTER_GRADIENT = "0.86";
    static final String CSS_OPACITY_BUTTON_DISABLED = "0.88";
    static final String CSS_OPACITY_FULL = "1";
    static final String CSS_OPACITY_FULL_DECIMAL = "1.0";

    static final Color GRADIENT_OVERLAY_COLOR = Color.rgb(6, 23, 32);
    static final String GRADIENT_STOP_00 = "#071d29";
    static final String GRADIENT_STOP_01 = "#092937";
    static final String GRADIENT_STOP_02 = "#0b3c48";
    static final String GRADIENT_STOP_03 = "#075a5a";
    static final String GRADIENT_STOP_04 = "#08786d";
    static final String GRADIENT_STOP_05 = "#1f8a78";
    static final String GRADIENT_STOP_06 = "#466f78";
    static final String GRADIENT_STOP_07 = "#215b73";
    static final String GRADIENT_STOP_08 = "#134b62";
    static final String GRADIENT_STOP_09 = "#0d3548";
    static final String GRADIENT_STOP_10 = "#092937";
    static final String GRADIENT_STOP_11 = "#071d29";

    static final List<CssToken> CSS_TOKENS = List.of(
            new CssToken("-launcher-color-paper", "#f4f7f8"),
            new CssToken("-launcher-color-teal-dark", "#0d4f55"),
            new CssToken("-launcher-color-ink", "#172431"),
            new CssToken("-launcher-color-rgba-0-0-0-0p18", "rgba(0, 0, 0, 0.18)"),
            new CssToken("-launcher-color-teal", "#1f7a7a"),
            new CssToken("-launcher-color-white", "white"),
            new CssToken("-launcher-color-teal-border", "#17696d"),
            new CssToken("-launcher-color-surface-soft", "#f9fcfd"),
            new CssToken("-launcher-color-control-border-soft", "#b9cdd3"),
            new CssToken("-launcher-color-rgba-255-255-255-0p92", "rgba(255, 255, 255, 0.92)"),
            new CssToken("-launcher-color-rgba-255-255-255-0p95", "rgba(255, 255, 255, 0.95)"),
            new CssToken("-launcher-color-rgba-255-255-255-0p94", "rgba(255, 255, 255, 0.94)"),
            new CssToken("-launcher-color-disabled-text", "#49656d"),
            new CssToken("-launcher-color-danger-surface", "#ffe8e2"),
            new CssToken("-launcher-color-danger-text", "#7c2417"),
            new CssToken("-launcher-color-danger-border", "#f0a090"),
            new CssToken("-launcher-color-success-surface", "#ecf9f1"),
            new CssToken("-launcher-color-success-text", "#17623b"),
            new CssToken("-launcher-color-success-border", "#9fd9b7"),
            new CssToken("-launcher-color-neutral-control-surface", "#e6f0f2"),
            new CssToken("-launcher-color-neutral-control-border", "#b5cbd2"),
            new CssToken("-launcher-color-white-hex", "#ffffff"),
            new CssToken("-launcher-color-surface-border", "#c8dce1"),
            new CssToken("-launcher-color-control-border", "#7fa3ad"),
            new CssToken("-launcher-color-pressed-surface", "#eef6f7"),
            new CssToken("-launcher-color-rgba-0-0-0-0p14", "rgba(0, 0, 0, 0.14)"),
            new CssToken("-launcher-color-gold", "#d4a72c"),
            new CssToken("-launcher-color-muted", "#5f7080"),
            new CssToken("-launcher-color-warning-surface", "#fff5d0"),
            new CssToken("-launcher-color-warning-text", "#6f5200"),
            new CssToken("-launcher-color-hex-dcefee", "#dcefee"),
            new CssToken("-launcher-color-hex-b8d4d6", "#b8d4d6"),
            new CssToken("-launcher-color-transparent", "transparent"),
            new CssToken("-launcher-color-rgba-255-255-255-0p18", "rgba(255, 255, 255, 0.18)"),
            new CssToken("-launcher-color-hex-bfd3d4", "#bfd3d4"),
            new CssToken("-launcher-color-rgba-255-255-255-0p22", "rgba(255, 255, 255, 0.22)"),
            new CssToken("-launcher-color-hex-d9e7e8", "#d9e7e8"),
            new CssToken("-launcher-color-rgba-255-255-255-0p11", "rgba(255, 255, 255, 0.11)"),
            new CssToken("-launcher-color-hex-e8f6f7", "#e8f6f7"),
            new CssToken("-launcher-color-hex-c4dadd", "#c4dadd"),
            new CssToken("-launcher-color-rgba-191-226-228-0p86", "rgba(191, 226, 228, 0.86)"),
            new CssToken("-launcher-color-rgba-252-205-184-0p9", "rgba(252, 205, 184, 0.9)"),
            new CssToken("-launcher-color-rgba-219-235-238-0p86", "rgba(219, 235, 238, 0.86)"),
            new CssToken("-launcher-color-hex-bfd3d8", "#bfd3d8"),
            new CssToken("-launcher-color-hex-f7fbfc", "#f7fbfc"),
            new CssToken("-launcher-color-hex-d2e3e7", "#d2e3e7"),
            new CssToken("-launcher-color-hex-314955", "#314955"),
            new CssToken("-launcher-color-hex-edf4f5", "#edf4f5"),
            new CssToken("-launcher-color-hex-0d7f80", "#0d7f80"),
            new CssToken("-launcher-color-hex-07595d", "#07595d"),
            new CssToken("-launcher-color-hex-cfdce1", "#cfdce1"),
            new CssToken("-launcher-color-rgba-23-36-49-0p05", "rgba(23, 36, 49, 0.05)"),
            new CssToken("-launcher-color-hex-f8fbfc", "#f8fbfc"),
            new CssToken("-launcher-color-hex-bed3da", "#bed3da"),
            new CssToken("-launcher-color-rgba-13-79-85-0p045", "rgba(13, 79, 85, 0.045)"),
            new CssToken("-launcher-color-rgba-23-36-49-0p07", "rgba(23, 36, 49, 0.07)"),
            new CssToken("-launcher-color-hex-dff4f1", "#dff4f1"),
            new CssToken("-launcher-color-hex-f2fbfa", "#f2fbfa"),
            new CssToken("-launcher-color-hex-8dc7c3", "#8dc7c3"),
            new CssToken("-launcher-color-hex-e4eef3", "#e4eef3"),
            new CssToken("-launcher-color-hex-f5f8fa", "#f5f8fa"),
            new CssToken("-launcher-color-hex-aebfc7", "#aebfc7"),
            new CssToken("-launcher-color-hex-e3f2e2", "#e3f2e2"),
            new CssToken("-launcher-color-hex-f5fbf4", "#f5fbf4"),
            new CssToken("-launcher-color-hex-a9c9a4", "#a9c9a4"),
            new CssToken("-launcher-color-hex-fff0c8", "#fff0c8"),
            new CssToken("-launcher-color-hex-fffaf0", "#fffaf0"),
            new CssToken("-launcher-color-hex-e4be65", "#e4be65"),
            new CssToken("-launcher-color-hex-ffedaa", "#ffedaa"),
            new CssToken("-launcher-color-hex-fff8dc", "#fff8dc"),
            new CssToken("-launcher-color-hex-d7e2e6", "#d7e2e6"),
            new CssToken("-launcher-color-hex-f3fbfa", "#f3fbfa"),
            new CssToken("-launcher-color-hex-188a88", "#188a88"),
            new CssToken("-launcher-color-hex-6f8795", "#6f8795"),
            new CssToken("-launcher-color-hex-6d9969", "#6d9969"),
            new CssToken("-launcher-color-hex-c9902f", "#c9902f"),
            new CssToken("-launcher-color-hex-a47c10", "#a47c10"),
            new CssToken("-launcher-color-hex-def5f2", "#def5f2"),
            new CssToken("-launcher-color-hex-064f51", "#064f51"),
            new CssToken("-launcher-color-hex-e8f0f3", "#e8f0f3"),
            new CssToken("-launcher-color-hex-eef4ef", "#eef4ef"),
            new CssToken("-launcher-color-hex-3f5f3d", "#3f5f3d"),
            new CssToken("-launcher-color-hex-adc8aa", "#adc8aa"),
            new CssToken("-launcher-color-hex-fff1cc", "#fff1cc"),
            new CssToken("-launcher-color-hex-7a4e00", "#7a4e00"),
            new CssToken("-launcher-color-rgba-255-255-255-0p10", "rgba(255, 255, 255, 0.10)"),
            new CssToken("-launcher-color-rgba-31-122-122-0p28", "rgba(31, 122, 122, 0.28)"),
            new CssToken("-launcher-color-rgba-13-79-85-0p06", "rgba(13, 79, 85, 0.06)"),
            new CssToken("-launcher-color-rgba-31-122-122-0p22", "rgba(31, 122, 122, 0.22)"),
            new CssToken("-launcher-color-hex-425664", "#425664"),
            new CssToken("-launcher-color-rgba-13-79-85-0p08", "rgba(13, 79, 85, 0.08)"),
            new CssToken("-launcher-color-rgba-31-122-122-0p32", "rgba(31, 122, 122, 0.32)"),
            new CssToken("-launcher-color-rgba-255-255-255-0p00", "rgba(255, 255, 255, 0.00)"),
            new CssToken("-launcher-color-rgba-255-255-255-0p58", "rgba(255, 255, 255, 0.58)"),
            new CssToken("-launcher-color-rgba-31-138-120-0p48", "rgba(31, 138, 120, 0.48)"),
            new CssToken("-launcher-color-rgba-217-96-76-0p58", "rgba(217, 96, 76, 0.58)"),
            new CssToken("-launcher-color-hex-9f3427", "#9f3427"),
            new CssToken("-launcher-color-rgba-212-167-44-0p58", "rgba(212, 167, 44, 0.58)"),
            new CssToken("-launcher-color-rgba-15-73-82-0p42", "rgba(15, 73, 82, 0.42)"),
            new CssToken("-launcher-color-rgba-15-73-82-0p58", "rgba(15, 73, 82, 0.58)"),
            new CssToken("-launcher-color-rgba-15-73-82-0p08", "rgba(15, 73, 82, 0.08)"),
            new CssToken("-launcher-color-rgba-127-163-173-0p42", "rgba(127, 163, 173, 0.42)"),
            new CssToken("-launcher-color-rgba-255-255-255-0p68", "rgba(255, 255, 255, 0.68)"),
            new CssToken("-launcher-color-rgba-31-122-122-0p48", "rgba(31, 122, 122, 0.48)"),
            new CssToken("-launcher-color-rgba-31-122-122-0p065", "rgba(31, 122, 122, 0.065)"),
            new CssToken("-launcher-color-rgba-88-120-136-0p46", "rgba(88, 120, 136, 0.46)"),
            new CssToken("-launcher-color-rgba-88-120-136-0p07", "rgba(88, 120, 136, 0.07)"),
            new CssToken("-launcher-color-rgba-204-155-41-0p48", "rgba(204, 155, 41, 0.48)"),
            new CssToken("-launcher-color-rgba-204-155-41-0p07", "rgba(204, 155, 41, 0.07)"),
            new CssToken("-launcher-color-rgba-217-96-76-0p46", "rgba(217, 96, 76, 0.46)"),
            new CssToken("-launcher-color-rgba-217-96-76-0p06", "rgba(217, 96, 76, 0.06)"),
            new CssToken("-launcher-color-rgba-13-127-128-0p48", "rgba(13, 127, 128, 0.48)"),
            new CssToken("-launcher-color-rgba-13-127-128-0p065", "rgba(13, 127, 128, 0.065)"),
            new CssToken("-launcher-color-rgba-109-153-105-0p5", "rgba(109, 153, 105, 0.5)"),
            new CssToken("-launcher-color-rgba-109-153-105-0p07", "rgba(109, 153, 105, 0.07)"),
            new CssToken("-launcher-color-hex-41535f", "#41535f"),
            new CssToken("-launcher-color-hex-173747", "#173747"),
            new CssToken("-launcher-color-hex-1b4253", "#1b4253"),
            new CssToken("-launcher-color-hex-102e3d", "#102e3d"),
            new CssToken("-launcher-color-hex-dcebed", "#dcebed"),
            new CssToken("-launcher-color-hex-fbfdfe", "#fbfdfe"),
            new CssToken("-launcher-color-rgba-31-122-122-0p18", "rgba(31, 122, 122, 0.18)"),
            new CssToken("-launcher-color-hex-e6f4f5", "#e6f4f5"),
            new CssToken("-launcher-color-hex-758692", "#758692"),
            new CssToken("-launcher-color-hex-f3f7f8", "#f3f7f8"),
            new CssToken("-launcher-color-hex-9fb9c2", "#9fb9c2"),
            new CssToken("-launcher-color-hex-fffdf4", "#fffdf4"),
            new CssToken("-launcher-color-hex-d7b653", "#d7b653"),
            new CssToken("-launcher-color-hex-415866", "#415866"),
            new CssToken("-launcher-color-rgba-10-47-56-0p08", "rgba(10, 47, 56, 0.08)"),
            new CssToken("-launcher-color-hex-16a39c", "#16a39c"),
            new CssToken("-launcher-color-hex-9b3126", "#9b3126"),
            new CssToken("-launcher-color-hex-fff8df", "#fff8df"),
            new CssToken("-launcher-color-hex-f2b800", "#f2b800"),
            new CssToken("-launcher-color-hex-725f2a", "#725f2a"),
            new CssToken("-launcher-color-hex-e1d2a1", "#e1d2a1"),
            new CssToken("-launcher-color-hex-24323a", "#24323a"),
            new CssToken("-launcher-color-hex-062938", "#062938"),
            new CssToken("-launcher-color-hex-1f6071", "#1f6071"),
            new CssToken("-launcher-color-rgba-6-28-39-0p26", "rgba(6, 28, 39, 0.26)"),
            new CssToken("-launcher-color-hex-0d2430", "#0d2430"),
            new CssToken("-launcher-color-hex-375f6c", "#375f6c"),
            new CssToken("-launcher-color-hex-ffe0a3", "#ffe0a3"),
            new CssToken("-launcher-color-hex-bdf2d0", "#bdf2d0"),
            new CssToken("-launcher-color-hex-ffb8aa", "#ffb8aa"),
            new CssToken("-launcher-color-hex-061720", "#061720"),
            new CssToken("-launcher-color-hex-355b69", "#355b69"),
            new CssToken("-launcher-color-hex-ecfbf7", "#ecfbf7"),
            new CssToken("-launcher-color-hex-a9c8ce", "#a9c8ce"),
            new CssToken("-launcher-color-hex-64d7c7", "#64d7c7"),
            new CssToken("-launcher-color-hex-2f8077", "#2f8077"),
            new CssToken("-launcher-color-hex-0c2a2d", "#0c2a2d"),
            new CssToken("-launcher-color-hex-9fc4ff", "#9fc4ff"),
            new CssToken("-launcher-color-hex-4e6f9d", "#4e6f9d"),
            new CssToken("-launcher-color-hex-0d2235", "#0d2235"),
            new CssToken("-launcher-color-hex-c5adff", "#c5adff"),
            new CssToken("-launcher-color-hex-755fa6", "#755fa6"),
            new CssToken("-launcher-color-hex-191f37", "#191f37"),
            new CssToken("-launcher-color-hex-f5cf75", "#f5cf75"),
            new CssToken("-launcher-color-hex-967234", "#967234"),
            new CssToken("-launcher-color-hex-2c2412", "#2c2412"),
            new CssToken("-launcher-color-hex-b9c7cf", "#b9c7cf"),
            new CssToken("-launcher-color-hex-61727b", "#61727b"),
            new CssToken("-launcher-color-hex-172630", "#172630"),
            new CssToken("-launcher-color-hex-9ee0b8", "#9ee0b8"),
            new CssToken("-launcher-color-hex-4b8760", "#4b8760"),
            new CssToken("-launcher-color-hex-102a1f", "#102a1f"),
            new CssToken("-launcher-color-hex-341a18", "#341a18"),
            new CssToken("-launcher-color-hex-b9675b", "#b9675b"),
            new CssToken("-launcher-color-rgba-6-23-32-0p92", "rgba(6, 23, 32, 0.92)"),
            new CssToken("-launcher-color-rgba-11-44-58-0p34", "rgba(11, 44, 58, 0.34)"),
            new CssToken("-launcher-color-rgba-6-23-32-0p0", "rgba(6, 23, 32, 0.0)"),
            new CssToken("-launcher-color-hex-163748", "#163748"),
            new CssToken("-launcher-color-hex-eaf7f4", "#eaf7f4"),
            new CssToken("-launcher-color-hex-4d7583", "#4d7583"),
            new CssToken("-launcher-color-hex-dff4e8", "#dff4e8"),
            new CssToken("-launcher-color-hex-223846", "#223846"),
            new CssToken("-launcher-color-hex-cfe2e6", "#cfe2e6"),
            new CssToken("-launcher-color-hex-405b68", "#405b68"),
            new CssToken("-launcher-color-hex-eefaf6", "#eefaf6"),
            new CssToken("-launcher-color-hex-bcd3d8", "#bcd3d8"),
            new CssToken("-launcher-color-hex-102733", "#102733"),
            new CssToken("-launcher-color-hex-2f5360", "#2f5360"),
            new CssToken("-launcher-color-hex-8fb8c0", "#8fb8c0"),
            new CssToken("-launcher-color-hex-121d27", "#121d27"),
            new CssToken("-launcher-color-hex-586879", "#586879"),
            new CssToken("-launcher-color-hex-e6edf2", "#e6edf2"),
            new CssToken("-launcher-color-hex-0e2a34", "#0e2a34"),
            new CssToken("-launcher-color-hex-ff8f7f", "#ff8f7f"),
            new CssToken("-launcher-color-hex-ffd166", "#ffd166"),
            new CssToken("-launcher-color-hex-8ee6a8", "#8ee6a8"),
            new CssToken("-launcher-color-hex-f4c56a", "#f4c56a"),
            new CssToken("-launcher-color-hex-7d92a0", "#7d92a0"),
            new CssToken("-launcher-color-hex-6fd6cb", "#6fd6cb"),
            new CssToken("-launcher-color-hex-7ca1ad", "#7ca1ad"),
            new CssToken("-launcher-color-hex-ffd3cb", "#ffd3cb"),
            new CssToken("-launcher-color-hex-c5f2d2", "#c5f2d2"),
            new CssToken("-launcher-color-hex-ffe8b8", "#ffe8b8"),
            new CssToken("-launcher-color-hex-9fb3bd", "#9fb3bd"),
            new CssToken("-launcher-color-hex-a98234", "#a98234"),
            new CssToken("-launcher-color-hex-4d9a66", "#4d9a66"),
            new CssToken("-launcher-color-hex-3b6670", "#3b6670"),
            new CssToken("-launcher-color-hex-06202b", "#06202b"),
            new CssToken("-launcher-color-hex-b9efe6", "#b9efe6"),
            new CssToken("-launcher-color-hex-9eb9bf", "#9eb9bf"),
            new CssToken("-launcher-color-hex-49636b", "#49636b"),
            new CssToken("-launcher-color-hex-e7fbf5", "#e7fbf5"),
            new CssToken("-launcher-color-hex-ffe1dc", "#ffe1dc"),
            new CssToken("-launcher-color-hex-ffad9f", "#ffad9f"),
            new CssToken("-launcher-color-hex-ffe9e5", "#ffe9e5"),
            new CssToken("-launcher-color-field-background", "#fbfdff"),
            new CssToken("-launcher-color-hex-526a74", "#526a74"),
            new CssToken("-launcher-color-hex-f4f8f9", "#f4f8f9"),
            new CssToken("-launcher-color-hex-b7cbd1", "#b7cbd1"),
            new CssToken("-launcher-color-hex-e8f3f4", "#e8f3f4"),
            new CssToken("-launcher-color-hex-dbe8ec", "#dbe8ec"),
            new CssToken("-launcher-color-hex-39c5bb", "#39c5bb"),
            new CssToken("-launcher-color-changed-value-shadow", CHANGED_VALUE_SHADOW),
            new CssToken("-launcher-opacity-hidden", CSS_OPACITY_HIDDEN),
            new CssToken("-launcher-opacity-subtle", CSS_OPACITY_SUBTLE),
            new CssToken("-launcher-opacity-muted", CSS_OPACITY_MUTED),
            new CssToken("-launcher-opacity-low-gradient", CSS_OPACITY_LOW_GRADIENT),
            new CssToken("-launcher-opacity-focused-gradient", CSS_OPACITY_FOCUSED_GRADIENT),
            new CssToken("-launcher-opacity-success-gradient", CSS_OPACITY_SUCCESS_GRADIENT),
            new CssToken("-launcher-opacity-setting-accent", CSS_OPACITY_SETTING_ACCENT),
            new CssToken("-launcher-opacity-separator", CSS_OPACITY_SEPARATOR),
            new CssToken("-launcher-opacity-shimmer", CSS_OPACITY_SHIMMER),
            new CssToken("-launcher-opacity-parameter-accent", CSS_OPACITY_PARAMETER_ACCENT),
            new CssToken("-launcher-opacity-header-disabled", CSS_OPACITY_HEADER_DISABLED),
            new CssToken("-launcher-opacity-footer-gradient", CSS_OPACITY_FOOTER_GRADIENT),
            new CssToken("-launcher-opacity-button-disabled", CSS_OPACITY_BUTTON_DISABLED),
            new CssToken("-launcher-opacity-full", CSS_OPACITY_FULL),
            new CssToken("-launcher-opacity-full-decimal", CSS_OPACITY_FULL_DECIMAL)
    );

    static final Set<String> CSS_OPACITY_VALUES = Set.of(
            CSS_OPACITY_HIDDEN,
            CSS_OPACITY_SUBTLE,
            CSS_OPACITY_MUTED,
            CSS_OPACITY_LOW_GRADIENT,
            CSS_OPACITY_FOCUSED_GRADIENT,
            CSS_OPACITY_SUCCESS_GRADIENT,
            CSS_OPACITY_SETTING_ACCENT,
            CSS_OPACITY_SEPARATOR,
            CSS_OPACITY_SHIMMER,
            CSS_OPACITY_PARAMETER_ACCENT,
            CSS_OPACITY_HEADER_DISABLED,
            CSS_OPACITY_FOOTER_GRADIENT,
            CSS_OPACITY_BUTTON_DISABLED,
            CSS_OPACITY_FULL,
            CSS_OPACITY_FULL_DECIMAL
    );

    private LauncherThemeTokens() {
    }

    static String lookup(String value) {
        for (CssToken token : CSS_TOKENS) {
            if (token.value().equals(value)) {
                return token.name();
            }
        }
        throw new IllegalArgumentException("No launcher theme token for " + value);
    }

    static Paint tabSheenPaint() {
        double start = LauncherGeometryTokens.FLUSH;
        double end = LauncherGeometryTokens.SINGLE_COUNT;
        double midpoint = end / LauncherGeometryTokens.BILATERAL_EDGE_COUNT;
        return new LinearGradient(
                start,
                start,
                start,
                end,
                true,
                CycleMethod.NO_CYCLE,
                new Stop(start, TAB_SHEEN_HIGHLIGHT),
                new Stop(midpoint, TAB_SHEEN_BODY),
                new Stop(end, TAB_SHEEN_TAIL));
    }

    static String cssDeclarations() {
        StringBuilder builder = new StringBuilder();
        for (CssToken token : CSS_TOKENS) {
            builder.append("    ").append(token.name()).append(": ")
                    .append(token.value()).append(";\n");
        }
        return builder.toString();
    }

    record CssToken(String name, String value) {
    }
}
