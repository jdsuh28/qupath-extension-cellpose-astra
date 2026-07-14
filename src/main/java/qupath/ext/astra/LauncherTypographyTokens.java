package qupath.ext.astra;

import java.util.Locale;

final class LauncherTypographyTokens {

    static final String PRIMARY_FONT_STACK =
            "\"Inter\", \"Aptos Display\", \"Segoe UI\", \"Helvetica Neue\", Arial, sans-serif";
    static final String SOFT_FONT_STACK =
            "\"Avenir\", \"Nunito Sans\", \"Aptos\", \"Segoe UI\", \"Helvetica Neue\", Arial, sans-serif";
    static final String MONO_FONT_STACK =
            "\"JetBrains Mono\", \"SF Mono\", Consolas, monospace";
    static final String FONT_WEIGHT_NORMAL = "normal";
    static final String FONT_WEIGHT_BOLD = "bold";

    static final double FONT_SIZE_TIMELINE_DURATION = 9.0;
    static final double FONT_SIZE_BADGE_TINY = 9.5;
    static final double FONT_SIZE_CAPTION = 10.0;
    static final double FONT_SIZE_COMPACT = 10.5;
    static final double FONT_SIZE_LOG_DETAIL = 10.8;
    static final double FONT_SIZE_SMALL = 11.0;
    static final double FONT_SIZE_DESCRIPTION = 11.5;
    static final double FONT_SIZE_BODY = 12.0;
    static final double FONT_SIZE_LARGE_BODY = 12.5;
    static final double FONT_SIZE_CARD_LABEL = 13.0;
    static final double FONT_SIZE_HEADER_SUBTITLE = 13.5;
    static final double FONT_SIZE_CARD_TITLE = 14.0;
    static final double FONT_SIZE_FOCUSED_TITLE = 16.0;
    static final double FONT_SIZE_SECTION_TITLE = 18.0;
    static final double FONT_SIZE_DIALOG_TITLE = 20.0;
    static final double FONT_SIZE_HELP_TITLE = 22.0;
    static final double FONT_SIZE_HEADER_TITLE = 28.0;

    static final double TEXT_OPTICAL_INSET_CORRECTION =
            LauncherGeometryTokens.FLUSH;

    private LauncherTypographyTokens() {
    }

    static String cssSize(double value) {
        if (Math.rint(value) == value) {
            return String.format(Locale.ROOT, "%.0fpx", value);
        }
        return String.format(Locale.ROOT, "%.6fpx", value)
                .replaceAll("0+px$", "px")
                .replace(".px", "px");
    }
}
