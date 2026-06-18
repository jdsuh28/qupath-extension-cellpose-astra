package qupath.ext.astra;

import java.nio.IntBuffer;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Header container that moves a cached gradient strip behind static header
 * content.
 */
final class AnimatedGradientHeader extends StackPane {

    private static final double CYCLE_SECONDS = 16.0d;
    private static final double TEXTURE_SCALE = 3.0d;
    private static final double GRADIENT_SPAN_MULTIPLIER = 3.0d;
    private static final int TEXTURE_MAX_PIXEL_HEIGHT = 128;
    private static final double DITHER_AMPLITUDE = 1.2d / 255.0d;
    private static final double OVERLAY_ALPHA = 0.18d;
    private static final Color OVERLAY_COLOR = Color.rgb(6, 23, 32);
    private static final WritablePixelFormat<IntBuffer> ARGB_FORMAT = PixelFormat.getIntArgbInstance();
    private static final Stop[] STOPS = {
            new Stop(0.00d, Color.web("#071d29")),
            new Stop(0.08d, Color.web("#092937")),
            new Stop(0.16d, Color.web("#0b3c48")),
            new Stop(0.24d, Color.web("#075a5a")),
            new Stop(0.32d, Color.web("#08786d")),
            new Stop(0.42d, Color.web("#1f8a78")),
            new Stop(0.52d, Color.web("#466f78")),
            new Stop(0.62d, Color.web("#215b73")),
            new Stop(0.72d, Color.web("#134b62")),
            new Stop(0.82d, Color.web("#0d3548")),
            new Stop(0.92d, Color.web("#092937")),
            new Stop(1.00d, Color.web("#071d29"))
    };

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
    }

    private final Pane stripLayer = new Pane();
    private final ImageView leadingStrip = new ImageView();
    private final ImageView trailingStrip = new ImageView();
    private final TranslateTransition animation = new TranslateTransition(Duration.seconds(CYCLE_SECONDS), stripLayer);
    private HeaderMode headerMode = HeaderMode.DYNAMIC;
    private MotionSpeed motionSpeed = MotionSpeed.SMOOTH;
    private double stripLogicalWidth;

    /**
     * Creates a header with animated gradient paint behind the supplied content.
     *
     * @param content header content to place above the animated background.
     */
    AnimatedGradientHeader(Node content) {
        getStyleClass().add("astra-animated-gradient-header");
        setMaxWidth(Double.MAX_VALUE);
        if (content instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }

        stripLayer.setManaged(false);
        stripLayer.setMouseTransparent(true);
        configureImageView(leadingStrip);
        configureImageView(trailingStrip);
        stripLayer.getChildren().addAll(leadingStrip, trailingStrip);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(12.0d);
        clip.setArcHeight(12.0d);
        setClip(clip);

        animation.setInterpolator(Interpolator.LINEAR);
        animation.setCycleCount(Animation.INDEFINITE);

        getChildren().addAll(stripLayer, content);

        widthProperty().addListener((obs, oldValue, newValue) -> rebuildGradientStrip());
        heightProperty().addListener((obs, oldValue, newValue) -> rebuildGradientStrip());
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                animation.stop();
            } else {
                startAnimation();
            }
        });
        rebuildGradientStrip();
    }

    private static void configureImageView(ImageView imageView) {
        imageView.setManaged(false);
        imageView.setMouseTransparent(true);
        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        imageView.setCache(true);
    }

    /**
     * Generates one seamless gradient strip and positions a second copy after it
     * so translation can loop without repainting pixels each frame.
     */
    private void rebuildGradientStrip() {
        double width = Math.max(1.0d, getWidth());
        double height = Math.max(1.0d, getHeight());
        stripLayer.resizeRelocate(0.0d, 0.0d, width, height);

        stripLogicalWidth = Math.max(width + 1.0d, width * GRADIENT_SPAN_MULTIPLIER);
        WritableImage texture = createGradientTexture(stripLogicalWidth, height);

        configureStrip(leadingStrip, texture, 0.0d, height);
        configureStrip(trailingStrip, texture, stripLogicalWidth, height);

        animation.stop();
        stripLayer.setTranslateX(0.0d);
        animation.setFromX(0.0d);
        animation.setToX(-stripLogicalWidth);
        if (getScene() != null && headerMode == HeaderMode.DYNAMIC) {
            startAnimation();
        }
    }

    private void configureStrip(ImageView imageView, WritableImage texture, double layoutX, double height) {
        imageView.setImage(texture);
        imageView.setLayoutX(layoutX);
        imageView.setLayoutY(0.0d);
        imageView.setFitWidth(stripLogicalWidth);
        imageView.setFitHeight(height);
    }

    private void startAnimation() {
        animation.stop();
        stripLayer.setTranslateX(0.0d);
        animation.setDuration(Duration.seconds(motionSpeed.cycleSeconds));
        animation.playFromStart();
    }

    void setHeaderMode(HeaderMode nextMode) {
        headerMode = nextMode == null ? HeaderMode.DYNAMIC : nextMode;
        if (headerMode == HeaderMode.DYNAMIC && getScene() != null) {
            startAnimation();
        } else {
            animation.stop();
            stripLayer.setTranslateX(0.0d);
        }
    }

    void setMotionSpeed(MotionSpeed nextSpeed) {
        motionSpeed = nextSpeed == null ? MotionSpeed.SMOOTH : nextSpeed;
        if (headerMode == HeaderMode.DYNAMIC && getScene() != null) {
            startAnimation();
        }
    }

    /**
     * Generates a cached raster texture. The deterministic dither breaks color
     * bands without adding animated noise.
     */
    private static WritableImage createGradientTexture(double logicalWidth, double logicalHeight) {
        int pixelWidth = Math.max(2, (int) Math.ceil(logicalWidth * TEXTURE_SCALE));
        int pixelHeight = Math.max(2, Math.min(TEXTURE_MAX_PIXEL_HEIGHT, (int) Math.ceil(logicalHeight * TEXTURE_SCALE)));
        WritableImage texture = new WritableImage(pixelWidth, pixelHeight);

        double[] red = new double[pixelWidth];
        double[] green = new double[pixelWidth];
        double[] blue = new double[pixelWidth];
        for (int x = 0; x < pixelWidth; x++) {
            double position = x / (double) Math.max(1, pixelWidth - 1);
            Color base = colorAt(position);
            red[x] = overlay(base.getRed(), OVERLAY_COLOR.getRed());
            green[x] = overlay(base.getGreen(), OVERLAY_COLOR.getGreen());
            blue[x] = overlay(base.getBlue(), OVERLAY_COLOR.getBlue());
        }

        int[] row = new int[pixelWidth];
        var writer = texture.getPixelWriter();
        for (int y = 0; y < pixelHeight; y++) {
            for (int x = 0; x < pixelWidth; x++) {
                double dither = dither(x, y);
                row[x] = argb(red[x] + dither, green[x] + dither, blue[x] + dither);
            }
            writer.setPixels(0, y, pixelWidth, 1, ARGB_FORMAT, row, 0, pixelWidth);
        }
        return texture;
    }

    private static Color colorAt(double position) {
        double normalized = position - Math.floor(position);
        Stop previous = STOPS[0];
        for (int i = 1; i < STOPS.length; i++) {
            Stop next = STOPS[i];
            if (normalized <= next.getOffset()) {
                double range = next.getOffset() - previous.getOffset();
                double mix = range <= 0.0d ? 0.0d : (normalized - previous.getOffset()) / range;
                return previous.getColor().interpolate(next.getColor(), mix);
            }
            previous = next;
        }
        return STOPS[STOPS.length - 1].getColor();
    }

    private static double overlay(double channel, double overlayChannel) {
        return channel * (1.0d - OVERLAY_ALPHA) + overlayChannel * OVERLAY_ALPHA;
    }

    private static double dither(int x, int y) {
        int hash = x * 374_761_393 + y * 668_265_263;
        hash = (hash ^ (hash >>> 13)) * 1_274_126_177;
        hash ^= hash >>> 16;
        return (((hash & 0xff) / 255.0d) - 0.5d) * DITHER_AMPLITUDE;
    }

    private static int argb(double red, double green, double blue) {
        int r = toByte(red);
        int g = toByte(green);
        int b = toByte(blue);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    private static int toByte(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value * 255.0d)));
    }
}
