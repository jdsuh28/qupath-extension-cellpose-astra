package qupath.ext.astra;

import java.nio.IntBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import javafx.animation.AnimationTimer;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Stop;

/**
 * Shared animated ASTRA gradient paint used by header and run-progress surfaces.
 */
final class AnimatedGradientSurface extends Pane {

    static final String DIRECTION_PROPERTY = "astraGradientDirection";
    static final String MODE_PROPERTY = "astraGradientMode";
    static final String SPEED_PROPERTY = "astraGradientSpeed";
    static final String REPAINT_ELIGIBLE_PROPERTY = "astraGradientRepaintEligible";

    enum Direction {
        HORIZONTAL,
        VERTICAL
    }

    private static final double TEXTURE_SCALE = 3.0d;
    private static final double GRADIENT_SPAN_MULTIPLIER = 3.0d;
    private static final double SEAM_OVERLAP_LOGICAL_LENGTH = 1.0d / TEXTURE_SCALE;
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
    private static final Set<AnimatedGradientSurface> SURFACES =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean sharedClockRunning;
    private static final AnimationTimer SHARED_CLOCK = new AnimationTimer() {
        @Override
        public void handle(long now) {
            for (AnimatedGradientSurface surface : SURFACES) {
                if (surface.isRepaintEligible()) {
                    surface.applyAnimationFrame(now);
                }
            }
        }
    };

    private final ImageView leadingStrip = new ImageView();
    private final ImageView trailingStrip = new ImageView();
    private AnimatedGradientHeader.HeaderMode headerMode =
            AnimatedGradientHeader.HeaderMode.DYNAMIC;
    private AnimatedGradientHeader.MotionSpeed motionSpeed =
            AnimatedGradientHeader.MotionSpeed.SMOOTH;
    private Direction direction = Direction.HORIZONTAL;
    private double stripLogicalLength;

    AnimatedGradientSurface() {
        SURFACES.add(this);
        getStyleClass().add("astra-animated-gradient-surface");
        setManaged(false);
        setMouseTransparent(true);
        publishStateProperties();
        configureImageView(leadingStrip);
        configureImageView(trailingStrip);
        getChildren().addAll(leadingStrip, trailingStrip);

        widthProperty().addListener((obs, oldValue, newValue) -> rebuildGradientStrip());
        heightProperty().addListener((obs, oldValue, newValue) -> rebuildGradientStrip());
        sceneProperty().addListener((obs, oldScene, newScene) -> updateSharedClockState());
        visibleProperty().addListener((obs, oldValue, newValue) -> updateSharedClockState());
        parentProperty().addListener((obs, oldValue, newValue) -> updateSharedClockState());
        rebuildGradientStrip();
    }

    void setHeaderMode(AnimatedGradientHeader.HeaderMode nextMode) {
        headerMode = nextMode == null
                ? AnimatedGradientHeader.HeaderMode.DYNAMIC
                : nextMode;
        publishStateProperties();
        applyAnimationFrame(System.nanoTime());
        updateSharedClockState();
    }

    void setMotionSpeed(AnimatedGradientHeader.MotionSpeed nextSpeed) {
        motionSpeed = nextSpeed == null
                ? AnimatedGradientHeader.MotionSpeed.SMOOTH
                : nextSpeed;
        publishStateProperties();
        applyAnimationFrame(System.nanoTime());
        updateSharedClockState();
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

        configureStrip(leadingStrip, texture, width, height);
        configureStrip(trailingStrip, texture, width, height);
        applyAnimationFrame(System.nanoTime());
        updateSharedClockState();
    }

    private void configureStrip(ImageView imageView,
                                WritableImage texture,
                                double width,
                                double height) {
        imageView.setImage(texture);
        imageView.setFitWidth(direction == Direction.HORIZONTAL ? stripLogicalLength : width);
        imageView.setFitHeight(direction == Direction.VERTICAL ? stripLogicalLength : height);
    }

    private void applyAnimationFrame(long now) {
        if (stripLogicalLength <= 0.0d) {
            return;
        }
        double phase = headerMode == AnimatedGradientHeader.HeaderMode.DYNAMIC
                ? ((now / 1_000_000_000.0d) % motionSpeed.cycleSeconds())
                        / motionSpeed.cycleSeconds() * stripLogicalLength
                : 0.0d;
        if (direction == Direction.VERTICAL) {
            leadingStrip.setLayoutX(0.0d);
            trailingStrip.setLayoutX(0.0d);
            leadingStrip.setLayoutY(phase - stripLogicalLength);
            trailingStrip.setLayoutY(phase - SEAM_OVERLAP_LOGICAL_LENGTH);
        } else {
            leadingStrip.setLayoutX(-phase);
            trailingStrip.setLayoutX(stripLogicalLength - phase - SEAM_OVERLAP_LOGICAL_LENGTH);
            leadingStrip.setLayoutY(0.0d);
            trailingStrip.setLayoutY(0.0d);
        }
    }

    private void publishStateProperties() {
        getProperties().put(DIRECTION_PROPERTY, direction.name());
        getProperties().put(MODE_PROPERTY, headerMode.name());
        getProperties().put(SPEED_PROPERTY, motionSpeed.name());
        getProperties().put(REPAINT_ELIGIBLE_PROPERTY, Boolean.toString(isRepaintEligible()));
    }

    private static void updateSharedClockState() {
        SURFACES.forEach(AnimatedGradientSurface::publishEligibilityProperty);
        boolean anyAttached = SURFACES.stream().anyMatch(AnimatedGradientSurface::isRepaintEligible);
        if (anyAttached && !sharedClockRunning) {
            SHARED_CLOCK.start();
            sharedClockRunning = true;
        } else if (!anyAttached && sharedClockRunning) {
            SHARED_CLOCK.stop();
            sharedClockRunning = false;
        }
    }

    private void publishEligibilityProperty() {
        getProperties().put(REPAINT_ELIGIBLE_PROPERTY, Boolean.toString(isRepaintEligible()));
    }

    private boolean isRepaintEligible() {
        return getScene() != null
                && headerMode == AnimatedGradientHeader.HeaderMode.DYNAMIC
                && stripLogicalLength > 0.0d
                && getWidth() > 0.0d
                && getHeight() > 0.0d
                && getOpacity() > 0.0d
                && isTreeVisible();
    }

    private boolean isTreeVisible() {
        javafx.scene.Node current = this;
        while (current != null) {
            if (!current.isVisible()) {
                return false;
            }
            current = current.getParent();
        }
        return true;
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
                double dither = isSeamEdge(axis, axisPixels) ? 0.0d : dither(x, y);
                row[x] = argb(red[axis] + dither, green[axis] + dither, blue[axis] + dither);
            }
            writer.setPixels(0, y, pixelWidth, 1, ARGB_FORMAT, row, 0, pixelWidth);
        }
        return texture;
    }

    private static boolean isSeamEdge(int axis, int axisPixels) {
        return axis == 0 || axis == Math.max(0, axisPixels - 1);
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
