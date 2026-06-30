package qupath.ext.astra;

import javafx.geometry.Insets;

final class LauncherGeometryTokens {

    static final double FLUSH = 0.0;
    static final double LAYOUT_UNIT = 24.0;
    static final double OUTER_MARGIN = LAYOUT_UNIT;
    static final double INTRA_PANEL_MARGIN = OUTER_MARGIN / 2.0;
    static final double INTRA_PANEL_TIGHT_GAP = INTRA_PANEL_MARGIN / 3.0;
    static final double INTRA_PANEL_SUBTLE_GAP = INTRA_PANEL_MARGIN * 2.0 / 3.0;
    static final double SURFACE_BORDER_WIDTH = LAYOUT_UNIT / 24.0;
    static final double TOOLTIP_VERTICAL_INSET = INTRA_PANEL_TIGHT_GAP;
    static final double TOOLTIP_HORIZONTAL_INSET = INTRA_PANEL_SUBTLE_GAP;
    static final double ACTION_PROGRESS_HEIGHT = INTRA_PANEL_SUBTLE_GAP;
    static final double ACTION_PROGRESS_RADIUS = ACTION_PROGRESS_HEIGHT / 2.0;
    static final double ACTION_PROGRESS_MIN_WIDTH = LAYOUT_UNIT * 8.0;
    static final double ACTION_PROGRESS_TEXT_HEIGHT =
            INTRA_PANEL_MARGIN + INTRA_PANEL_TIGHT_GAP;
    static final double ACTION_PROGRESS_TEXT_TO_BAR_GAP =
            INTRA_PANEL_TIGHT_GAP;
    static final double ACTION_PROGRESS_TOTAL_HEIGHT =
            ACTION_PROGRESS_TEXT_HEIGHT
                    + ACTION_PROGRESS_TEXT_TO_BAR_GAP
                    + ACTION_PROGRESS_HEIGHT;
    static final double ACTION_PROGRESS_SHIMMER_WIDTH_DIVISOR =
            LAYOUT_UNIT / INTRA_PANEL_SUBTLE_GAP;
    static final double ACTION_PROGRESS_SHIMMER_SPEED_DIVISOR =
            LAYOUT_UNIT / INTRA_PANEL_TIGHT_GAP;
    static final double GRADIENT_SLOW_CYCLE_SECONDS =
            LAYOUT_UNIT;
    static final double GRADIENT_SMOOTH_CYCLE_SECONDS =
            LAYOUT_UNIT * 2.0 / 3.0;
    static final double GRADIENT_LIVELY_CYCLE_SECONDS =
            LAYOUT_UNIT / 6.0;
    static final double BUTTON_HEIGHT =
            LAYOUT_UNIT + INTRA_PANEL_SUBTLE_GAP + (SURFACE_BORDER_WIDTH * 2.0);
    static final double PANEL_NAV_BUTTON_MIN_WIDTH =
            LAYOUT_UNIT * 3.0;
    static final double INLINE_UTILITY_BUTTON_MIN_WIDTH =
            LAYOUT_UNIT * 7.0 / 2.0;
    static final double CONTROL_FIELD_HEIGHT =
            LAYOUT_UNIT + INTRA_PANEL_SUBTLE_GAP;
    static final double CONTROL_FIELD_MIN_WIDTH =
            LAYOUT_UNIT * 7.0;
    static final double LOG_FADE_VISIBLE_FRACTION =
            INTRA_PANEL_SUBTLE_GAP / LAYOUT_UNIT;

    private LauncherGeometryTokens() {
    }

    static Insets uniformOuterMargin() {
        return new Insets(OUTER_MARGIN);
    }

    static Insets intraPanelPadding() {
        return new Insets(INTRA_PANEL_MARGIN);
    }
}
