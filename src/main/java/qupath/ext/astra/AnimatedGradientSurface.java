package qupath.ext.astra;

import java.nio.IntBuffer;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.TranslateTransition;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Stop;
import javafx.util.Duration;

/**
 * Shared animated ASTRA gradient paint used by header and run-progress surfaces.
 */
final class AnimatedGradientSurface extends Pane {

    static final String DIRECTION_PROPERTY = "astraGradientDirection";
    static final String MODE_PROPERTY = "astraGradientMode";
    static final String SPEED_PROPERTY = "astraGradientSpeed";

    enum Direction {
        HORIZONTAL,
        VERTICAL
    }

    private static final double DEFAULT_CYCLE_SECONDS = 16.0d;
    private static final double TEXTURE_SCALE = 3.0d;
    private static final double GRADIENT_SPAN_MULTIPLIER = 3.0d;
    private static final int TEXTURE_MAX_PIXEL_HEIGHT = 128;
    private static final double DITHER_AMPLITUDE = 1.2d / 255.0d;
    private static final double OVERLAY_ALPHA = 0.18d;
    private static final Color OVERLAY_COLOR = Color.rgb(6, 23, 32);
    private static final WritablePixelFormat<IntBuffer> ARGB_FORMAT =
            PixelFormat.getIntArgbInstance();
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

    private final ImageView leadingStrip = new ImageView();
    private final ImageView trailingStrip = new ImageView();
    private final TranslateTransition animation =
            new TranslateTransition(Duration.seconds(DEFAULT_CYCLE_SECONDS), this);
    private AnimatedGradientHeader.HeaderMode headerMode =
            AnimatedGradientHeader.HeaderMode.DYNAMIC;
    private AnimatedGradientHeader.MotionSpeed motionSpeed =
            AnimatedGradientHeader.MotionSpeed.SMOOTH;
    private Direction direction = Direction.HORIZONTAL;
    private double stripLogicalLength;

    AnimatedGradientSurface() {
        getStyleClass().add("astra-animated-gradient-surface");
        setManaged(false);
        setMouseTransparent(true);
        publishStateProperties();
        configureImageView(leadingStrip);
        configureImageView(trailingStrip);
        getChildren().addAll(leadingStrip, trailingStrip);

        animation.setInterpolator(Interpolator.LINEAR);
        animation.setCycleCount(Animation.INDEFINITE);

        widthProperty().addListener((obs, oldValue, newValue) -> rebuildGradientStrip());
        heightProperty().addListener((obs, oldValue, newValue) -> rebuildGradientStrip());
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                animation.stop();
            } else {
                startAnimationIfNeeded();
            }
        });
        rebuildGradientStrip();
    }

    void setHeaderMode(AnimatedGradientHeader.HeaderMode nextMode) {
        headerMode = nextMode == null
                ? AnimatedGradientHeader.HeaderMode.DYNAMIC
                : nextMode;
        publishStateProperties();
        if (headerMode == AnimatedGradientHeader.HeaderMode.DYNAMIC && getScene() != null) {
            startAnimation();
        } else {
            animation.stop();
            setTranslateX(0.0d);
            setTranslateY(0.0d);
        }
    }

    void setMotionSpeed(AnimatedGradientHeader.MotionSpeed nextSpeed) {
        motionSpeed = nextSpeed == null
                ? AnimatedGradientHeader.MotionSpeed.SMOOTH
                : nextSpeed;
        publishStateProperties();
        startAnimationIfNeeded();
    }

    void setDirection(Direction nextDirection) {
        Direction resolved = nextDirection == null ? Direction.HORIZONTAL : nextDirection;
        if (direction == resolved) {
            publishStateProperties();
            return;
        }
        direction = resolved;
        publishStateProperties();
        rebuildGradientStrip();
    }

    private static void configureImageView(ImageView imageView) {
        imageView.setManaged(false);
        imageView.setMouseTransparent(true);
        imageView.setPreserveRatio(false);
        imageView.setSmooth(true);
        imageView.setCache(true);
    }

    private void rebuildGradientStrip() {
        double width = Math.max(1.0d, getWidth());
        double height = Math.max(1.0d, getHeight());

        double axisLength = direction == Direction.VERTICAL ? height : width;
        double crossLength = direction == Direction.VERTICAL ? width : height;
        stripLogicalLength = Math.max(axisLength + 1.0d, axisLength * GRADIENT_SPAN_MULTIPLIER);
        WritableImage texture = direction == Direction.VERTICAL
                ? createGradientTexture(crossLength, stripLogicalLength, direction)
                : createGradientTexture(stripLogicalLength, crossLength, direction);

        configureStrip(leadingStrip, texture, 0.0d, width, height);
        configureStrip(trailingStrip, texture, stripLogicalLength, width, height);

        animation.stop();
        setTranslateX(0.0d);
        setTranslateY(0.0d);
        animation.setFromX(0.0d);
        animation.setFromY(0.0d);
        animation.setToX(direction == Direction.HORIZONTAL ? -stripLogicalLength : 0.0d);
        animation.setToY(direction == Direction.VERTICAL ? -stripLogicalLength : 0.0d);
        startAnimationIfNeeded();
    }

    private void configureStrip(ImageView imageView,
                                WritableImage texture,
                                double layoutAxisOffset,
                                double width,
                                double height) {
        imageView.setImage(texture);
        imageView.setLayoutX(direction == Direction.HORIZONTAL ? layoutAxisOffset : 0.0d);
        imageView.setLayoutY(direction == Direction.VERTICAL ? layoutAxisOffset : 0.0d);
        imageView.setFitWidth(direction == Direction.HORIZONTAL ? stripLogicalLength : width);
        imageView.setFitHeight(direction == Direction.VERTICAL ? stripLogicalLength : height);
    }

    private void startAnimationIfNeeded() {
        if (getScene() != null && headerMode == AnimatedGradientHeader.HeaderMode.DYNAMIC) {
            startAnimation();
        }
    }

    private void startAnimation() {
        animation.stop();
        setTranslateX(0.0d);
        setTranslateY(0.0d);
        animation.setDuration(Duration.seconds(motionSpeed.cycleSeconds()));
        animation.playFromStart();
    }

    private void publishStateProperties() {
        getProperties().put(DIRECTION_PROPERTY, direction.name());
        getProperties().put(MODE_PROPERTY, headerMode.name());
        getProperties().put(SPEED_PROPERTY, motionSpeed.name());
    }

    private static WritableImage createGradientTexture(double logicalWidth,
                                                       double logicalHeight,
                                                       Direction direction) {
        int pixelWidth = Math.max(2, (int) Math.ceil(logicalWidth * TEXTURE_SCALE));
        int pixelHeight = Math.max(2, Math.min(TEXTURE_MAX_PIXEL_HEIGHT,
                (int) Math.ceil(logicalHeight * TEXTURE_SCALE)));
        WritableImage texture = new WritableImage(pixelWidth, pixelHeight);

        int axisPixels = direction == Direction.VERTICAL ? pixelHeight : pixelWidth;
        double[] red = new double[axisPixels];
        double[] green = new double[axisPixels];
        double[] blue = new double[axisPixels];
        for (int axis = 0; axis < axisPixels; axis++) {
            double position = axis / (double) Math.max(1, axisPixels - 1);
            Color base = colorAt(position);
            red[axis] = overlay(base.getRed(), OVERLAY_COLOR.getRed());
            green[axis] = overlay(base.getGreen(), OVERLAY_COLOR.getGreen());
            blue[axis] = overlay(base.getBlue(), OVERLAY_COLOR.getBlue());
        }

        int[] row = new int[pixelWidth];
        var writer = texture.getPixelWriter();
        for (int y = 0; y < pixelHeight; y++) {
            for (int x = 0; x < pixelWidth; x++) {
                int axis = direction == Direction.VERTICAL ? y : x;
                double dither = dither(x, y);
                row[x] = argb(red[axis] + dither, green[axis] + dither, blue[axis] + dither);
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
