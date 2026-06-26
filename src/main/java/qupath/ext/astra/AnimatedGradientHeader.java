package qupath.ext.astra;

import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/**
 * Header container that moves the shared ASTRA gradient behind static content.
 */
final class AnimatedGradientHeader extends StackPane {

    private static final double HEADER_CLIP_ARC =
            LauncherGeometryTokens.INTRA_PANEL_MARGIN;

    enum HeaderMode {
        STATIC,
        DYNAMIC
    }

    enum MotionSpeed {
        SLOW("Slow", 24.0d),
        SMOOTH("Smooth", 16.0d),
        LIVELY("Lively", 10.0d);

        private final String label;
        private final double cycleSeconds;

        MotionSpeed(String label, double cycleSeconds) {
            this.label = label;
            this.cycleSeconds = cycleSeconds;
        }

        String label() {
            return label;
        }

        double cycleSeconds() {
            return cycleSeconds;
        }
    }

    private final AnimatedGradientSurface gradientSurface = new AnimatedGradientSurface();

    AnimatedGradientHeader(Node content) {
        getStyleClass().add("astra-animated-gradient-header");
        setMaxWidth(Double.MAX_VALUE);
        if (content instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }

        widthProperty().addListener((obs, oldValue, newValue) -> resizeGradientSurface());
        heightProperty().addListener((obs, oldValue, newValue) -> resizeGradientSurface());

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(HEADER_CLIP_ARC);
        clip.setArcHeight(HEADER_CLIP_ARC);
        setClip(clip);

        getChildren().addAll(gradientSurface, content);
        resizeGradientSurface();
    }

    private void resizeGradientSurface() {
        gradientSurface.resizeRelocate(
                0.0d,
                0.0d,
                Math.max(1.0d, getWidth()),
                Math.max(1.0d, getHeight()));
    }

    void setHeaderMode(HeaderMode nextMode) {
        gradientSurface.setHeaderMode(nextMode);
    }

    void setMotionSpeed(MotionSpeed nextSpeed) {
        gradientSurface.setMotionSpeed(nextSpeed);
    }
}
