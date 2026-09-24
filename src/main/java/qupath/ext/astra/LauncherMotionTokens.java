package qupath.ext.astra;

import java.time.Duration;

final class LauncherMotionTokens {

    static final double RUN_LOG_ELAPSED_REFRESH_SECONDS =
            LauncherGeometryTokens.SINGLE_COUNT;
    static final double AUTOSAVE_DEBOUNCE_MILLIS =
            350.0d;
    static final double COPY_FEEDBACK_SECONDS =
            1.2d;

    static final Duration RUNTIME_COMMAND_TIMEOUT =
            Duration.ofMinutes(45L);
    static final Duration RUNTIME_BOOTSTRAP_TIMEOUT =
            Duration.ofMinutes(20L);
    static final Duration RUNTIME_PROBE_TIMEOUT =
            Duration.ofSeconds(RUNTIME_BOOTSTRAP_TIMEOUT.toMinutes());
    static final Duration RUNTIME_NETWORK_TIMEOUT =
            Duration.ofSeconds(30L);
    static final Duration RUNTIME_VALIDATION_TIMEOUT =
            Duration.ofMinutes(Math.round(LauncherGeometryTokens.BILATERAL_EDGE_COUNT));
    static final Duration RUNTIME_CANCELLATION_GRACE =
            Duration.ofSeconds(RUNTIME_VALIDATION_TIMEOUT.toMinutes());

    static final double GRADIENT_TEXTURE_SCALE =
            LauncherGeometryTokens.TRILATERAL_EDGE_COUNT;
    static final double GRADIENT_SPAN_MULTIPLIER =
            GRADIENT_TEXTURE_SCALE;
    static final double GRADIENT_TEXTURE_PIXEL_UNIT =
            LauncherGeometryTokens.SINGLE_COUNT;
    static final double GRADIENT_SEAM_OVERLAP_LOGICAL_LENGTH =
            GRADIENT_TEXTURE_PIXEL_UNIT / GRADIENT_TEXTURE_SCALE;
    static final int GRADIENT_TEXTURE_MAX_PIXEL_HEIGHT =
            (int) Math.round(
                    LauncherGeometryTokens.LAYOUT_UNIT
                            * LauncherGeometryTokens.INTRA_PANEL_SUBTLE_GAP
                            / LauncherGeometryTokens.SURFACE_BORDER_WIDTH
                            * LauncherGeometryTokens.BILATERAL_EDGE_COUNT
                            / LauncherGeometryTokens.TRILATERAL_EDGE_COUNT);
    static final double GRADIENT_DITHER_AMPLITUDE =
            1.2d / 255.0d;
    static final double GRADIENT_OVERLAY_ALPHA =
            Double.parseDouble(LauncherThemeTokens.CSS_OPACITY_SUBTLE);
    static final double GRADIENT_FULL_ALPHA =
            LauncherGeometryTokens.SINGLE_COUNT;
    static final int GRADIENT_MIN_TEXTURE_PIXELS =
            (int) Math.round(LauncherGeometryTokens.BILATERAL_EDGE_COUNT);

    private LauncherMotionTokens() {
    }
}
