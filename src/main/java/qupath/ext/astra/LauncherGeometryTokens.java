package qupath.ext.astra;

import javafx.geometry.Insets;

final class LauncherGeometryTokens {

    static final double FLUSH = 0.0;
    static final double LAYOUT_UNIT = 24.0;
    static final double SINGLE_COUNT = LAYOUT_UNIT / LAYOUT_UNIT;
    static final double BILATERAL_EDGE_COUNT = SINGLE_COUNT + SINGLE_COUNT;
    static final double TRILATERAL_EDGE_COUNT = BILATERAL_EDGE_COUNT + SINGLE_COUNT;
    static final double QUADRILATERAL_EDGE_COUNT =
            BILATERAL_EDGE_COUNT + BILATERAL_EDGE_COUNT;
    static final double OUTER_MARGIN = LAYOUT_UNIT;
    static final double INTRA_PANEL_MARGIN = OUTER_MARGIN / BILATERAL_EDGE_COUNT;
    static final double INTRA_PANEL_TIGHT_GAP = INTRA_PANEL_MARGIN / TRILATERAL_EDGE_COUNT;
    static final double INTRA_PANEL_SUBTLE_GAP =
            INTRA_PANEL_MARGIN * BILATERAL_EDGE_COUNT / TRILATERAL_EDGE_COUNT;
    static final double SURFACE_BORDER_WIDTH = LAYOUT_UNIT / 24.0;
    static final double NO_BORDER_WIDTH = FLUSH;
    static final double BILATERAL_BORDER_WIDTH =
            SURFACE_BORDER_WIDTH * BILATERAL_EDGE_COUNT;
    static final double BEVEL_DIAMETER_DIVISOR = OUTER_MARGIN / INTRA_PANEL_MARGIN;
    static final double CUBIC_ARC_HANDLE_NUMERATOR = INTRA_PANEL_TIGHT_GAP;
    static final double CUBIC_ARC_HANDLE_DENOMINATOR =
            INTRA_PANEL_MARGIN - INTRA_PANEL_TIGHT_GAP;
    static final double CUBIC_ARC_HANDLE_RATIO =
            (Math.sqrt(BEVEL_DIAMETER_DIVISOR) - 1.0)
                    * CUBIC_ARC_HANDLE_NUMERATOR
                    / CUBIC_ARC_HANDLE_DENOMINATOR;
    static final double COMPACT_BEVEL_RADIUS = INTRA_PANEL_TIGHT_GAP;
    static final double CONTROL_BEVEL_RADIUS =
            COMPACT_BEVEL_RADIUS + SURFACE_BORDER_WIDTH;
    static final double SETTINGS_CARD_ACCENT_ARC = CONTROL_BEVEL_RADIUS;
    static final double SUBPANEL_BEVEL_RADIUS =
            INTRA_PANEL_SUBTLE_GAP - (SURFACE_BORDER_WIDTH * BEVEL_DIAMETER_DIVISOR);
    static final double CARD_BEVEL_RADIUS =
            INTRA_PANEL_SUBTLE_GAP - SURFACE_BORDER_WIDTH;
    static final double SURFACE_BEVEL_RADIUS = INTRA_PANEL_SUBTLE_GAP;
    static final double HELP_BEVEL_RADIUS =
            SURFACE_BEVEL_RADIUS + SURFACE_BORDER_WIDTH;
    static final double BADGE_BEVEL_RADIUS =
            INTRA_PANEL_MARGIN - SURFACE_BORDER_WIDTH;
    static final double FILLER_BEVEL_RADIUS = INTRA_PANEL_MARGIN;
    static final double DIALOG_BEVEL_RADIUS =
            INTRA_PANEL_MARGIN + (SURFACE_BORDER_WIDTH * BEVEL_DIAMETER_DIVISOR);
    static final double WORKFLOW_PILL_BEVEL_RADIUS =
            INTRA_PANEL_SUBTLE_GAP * BEVEL_DIAMETER_DIVISOR;
    static final double PRESSED_TRANSLATE_Y = FLUSH;
    static final double CHEVRON_OPTICAL_Y_OFFSET = -SURFACE_BORDER_WIDTH;
    static final double HIDDEN_CONTROL_SIZE = FLUSH;
    static final double SCROLLBAR_THUMB_GUTTER_DIVISOR =
            TRILATERAL_EDGE_COUNT;
    static final double SCROLLBAR_SIDE_PADDING_DIVISOR =
            BILATERAL_EDGE_COUNT;
    static final double TOOLTIP_VERTICAL_INSET = INTRA_PANEL_TIGHT_GAP;
    static final double TOOLTIP_HORIZONTAL_INSET = INTRA_PANEL_SUBTLE_GAP;
    static final double ACTION_PROGRESS_HEIGHT = INTRA_PANEL_SUBTLE_GAP;
    static final double ACTION_PROGRESS_RADIUS = COMPACT_BEVEL_RADIUS;
    static final double ACTION_PROGRESS_MIN_WIDTH =
            LAYOUT_UNIT
                    * ((LAYOUT_UNIT / INTRA_PANEL_TIGHT_GAP)
                    + BILATERAL_EDGE_COUNT);
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
            LAYOUT_UNIT + INTRA_PANEL_SUBTLE_GAP
                    + (SURFACE_BORDER_WIDTH * BILATERAL_EDGE_COUNT);
    static final double PANEL_NAV_BUTTON_MIN_WIDTH =
            LAYOUT_UNIT * 3.0;
    static final double INLINE_UTILITY_BUTTON_MIN_WIDTH =
            LAYOUT_UNIT * 7.0 / 2.0;
    static final double CONTROL_FIELD_HEIGHT =
            LAYOUT_UNIT + INTRA_PANEL_SUBTLE_GAP;
    static final double CONTROL_FIELD_MIN_WIDTH =
            LAYOUT_UNIT * 7.0;
    static final double SINGLE_LIST_WIDTH_SCALE =
            (INTRA_PANEL_SUBTLE_GAP - SURFACE_BORDER_WIDTH)
                    / CONTROL_BEVEL_RADIUS;
    static final double SINGLE_LIST_HEIGHT_SCALE =
            (INTRA_PANEL_SUBTLE_GAP - SURFACE_BORDER_WIDTH)
                    / INTRA_PANEL_SUBTLE_GAP;
    static final int MULTI_SELECT_SUMMARY_CHARACTER_LIMIT =
            (int)Math.round(
                    (LAYOUT_UNIT * BEVEL_DIAMETER_DIVISOR)
                            + (INTRA_PANEL_SUBTLE_GAP * BILATERAL_EDGE_COUNT));
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
