package qupath.ext.astra;

import java.nio.IntBuffer;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;

/**
 * Header container that paints a slow, continuous horizontal gradient behind
 * static header content.
 */
final class AnimatedGradientHeader extends StackPane {

    private static final long FRAME_INTERVAL_NANOS = 33_333_333L;
    private static final double CYCLE_SECONDS = 16.0d;
    private static final long CYCLE_NANOS = (long) (CYCLE_SECONDS * 1_000_000_000L);
    private static final double TEXTURE_SCALE = 3.0d;
    private static final double GRADIENT_SPAN_MULTIPLIER = 3.0d;
    private static final int TEXTURE_MAX_PIXEL_HEIGHT = 128;
    private static final double DITHER_AMPLITUDE = 1.2d / 255.0d;
    private static final double OVERLAY_ALPHA = 0.18d;
    private static final Color OVERLAY_COLOR = Color.rgb(6, 23, 32);
    private static final WritablePixelFormat<IntBuffer> ARGB_FORMAT = PixelFormat.getIntArgbInstance();
    private static final Stop[] STOPS = {
            new Stop(0.00d, Color.web("#0b222d")),
            new Stop(0.08d, Color.web("#0d2b38")),
            new Stop(0.16d, Color.web("#103542")),
            new Stop(0.24d, Color.web("#12444d")),
            new Stop(0.32d, Color.web("#15535a")),
            new Stop(0.40d, Color.web("#1d6062")),
            new Stop(0.50d, Color.web("#286d68")),
            new Stop(0.60d, Color.web("#226466")),
            new Stop(0.68d, Color.web("#1b5666")),
            new Stop(0.76d, Color.web("#164858")),
            new Stop(0.84d, Color.web("#123642")),
            new Stop(0.92d, Color.web("#0d2b38")),
            new Stop(1.00d, Color.web("#0b222d"))
    };

    private final Canvas canvas = new Canvas();
    private final AnimationTimer animation;
    private WritableImage gradientTexture;
    private double textureLogicalWidth;
    private int texturePixelWidth;
    private int texturePixelHeight;
    private long animationStartNanos;
    private long lastFrameNanos;

    /**
     * Creates a header with animated gradient paint behind the supplied content.
     *
     * @param content header content to place above the animated background.
     */
    AnimatedGradientHeader(Node content) {
        setStyle("-fx-background-color: #0d2430; -fx-border-color: #375f6c; -fx-border-radius: 6; -fx-background-radius: 6;");
        setMaxWidth(Double.MAX_VALUE);
        if (content instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        canvas.setManaged(false);
        canvas.setMouseTransparent(true);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        clip.setArcWidth(12.0d);
        clip.setArcHeight(12.0d);
        setClip(clip);

        getChildren().addAll(canvas, content);

        animation = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (now - lastFrameNanos < FRAME_INTERVAL_NANOS) {
                    return;
                }
                lastFrameNanos = now;
                draw(currentPhase(now));
            }
        };

        widthProperty().addListener((obs, oldValue, newValue) -> resizeCanvasAndDraw());
        heightProperty().addListener((obs, oldValue, newValue) -> resizeCanvasAndDraw());
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                animation.stop();
            } else {
                startAnimation();
            }
        });
        resizeCanvasAndDraw();
    }

    /**
     * Starts the animation from the current moment.
     */
    private void startAnimation() {
        animationStartNanos = System.nanoTime();
        lastFrameNanos = 0L;
        animation.start();
    }

    /**
     * Computes the current animation phase.
     *
     * @param now current monotonic time.
     * @return normalized phase in the range [0, 1).
     */
    private double currentPhase(long now) {
        if (animationStartNanos == 0L) {
            return 0.0d;
        }
        return ((now - animationStartNanos) % CYCLE_NANOS) / (double) CYCLE_NANOS;
    }

    /**
     * Keeps the visible canvas at logical size and rebuilds the cached gradient
     * texture only when layout changes.
     */
    private void resizeCanvasAndDraw() {
        double width = Math.max(1.0d, getWidth());
        double height = Math.max(1.0d, getHeight());
        if (canvas.getWidth() != width) {
            canvas.setWidth(width);
        }
        if (canvas.getHeight() != height) {
            canvas.setHeight(height);
        }

        canvas.setScaleX(1.0d);
        canvas.setScaleY(1.0d);
        canvas.setTranslateX(0.0d);
        canvas.setTranslateY(0.0d);

        rebuildGradientTexture(width, height);
        draw(currentPhase(System.nanoTime()));
    }

    /**
     * Generates one seamless gradient cycle as a cached raster texture. The
     * deterministic dither breaks color bands without adding animated noise.
     */
    private void rebuildGradientTexture(double width, double height) {
        textureLogicalWidth = Math.max(1.0d, width * GRADIENT_SPAN_MULTIPLIER * 2.0d);
        texturePixelWidth = Math.max(2, (int) Math.ceil(textureLogicalWidth * TEXTURE_SCALE));
        texturePixelHeight = Math.max(2, Math.min(TEXTURE_MAX_PIXEL_HEIGHT, (int) Math.ceil(height * TEXTURE_SCALE)));
        gradientTexture = new WritableImage(texturePixelWidth, texturePixelHeight);

        double[] red = new double[texturePixelWidth];
        double[] green = new double[texturePixelWidth];
        double[] blue = new double[texturePixelWidth];
        for (int x = 0; x < texturePixelWidth; x++) {
            double position = x / (double) Math.max(1, texturePixelWidth - 1);
            Color base = colorAt(position);
            red[x] = overlay(base.getRed(), OVERLAY_COLOR.getRed());
            green[x] = overlay(base.getGreen(), OVERLAY_COLOR.getGreen());
            blue[x] = overlay(base.getBlue(), OVERLAY_COLOR.getBlue());
        }

        int[] row = new int[texturePixelWidth];
        var writer = gradientTexture.getPixelWriter();
        for (int y = 0; y < texturePixelHeight; y++) {
            for (int x = 0; x < texturePixelWidth; x++) {
                double dither = dither(x, y);
                row[x] = argb(red[x] + dither, green[x] + dither, blue[x] + dither);
            }
            writer.setPixels(0, y, texturePixelWidth, 1, ARGB_FORMAT, row, 0, texturePixelWidth);
        }
    }

    /**
     * Paints one frame of the moving gradient.
     *
     * @param phase normalized animation phase.
     */
    private void draw(double phase) {
        double width = Math.max(1.0d, getWidth());
        double height = Math.max(1.0d, getHeight());

        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setTransform(1.0d, 0.0d, 0.0d, 1.0d, 0.0d, 0.0d);
        graphics.clearRect(0.0d, 0.0d, width, height);
        if (gradientTexture == null || textureLogicalWidth <= 0.0d) {
            return;
        }

        double sourceX = (phase * textureLogicalWidth) % textureLogicalWidth;
        double destinationX = 0.0d;
        double remaining = width;
        while (remaining > 0.0d) {
            double segmentWidth = Math.min(remaining, textureLogicalWidth - sourceX);
            double sourcePixelX = sourceX * texturePixelWidth / textureLogicalWidth;
            double sourcePixelWidth = Math.max(1.0d, segmentWidth * texturePixelWidth / textureLogicalWidth);
            graphics.drawImage(
                    gradientTexture,
                    sourcePixelX,
                    0.0d,
                    sourcePixelWidth,
                    texturePixelHeight,
                    destinationX,
                    0.0d,
                    segmentWidth,
                    height
            );
            remaining -= segmentWidth;
            destinationX += segmentWidth;
            sourceX = 0.0d;
        }
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
