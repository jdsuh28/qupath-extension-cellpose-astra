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

    private LauncherGeometryTokens() {
    }

    static Insets uniformOuterMargin() {
        return new Insets(OUTER_MARGIN);
    }

    static Insets intraPanelPadding() {
        return new Insets(INTRA_PANEL_MARGIN);
    }
}
