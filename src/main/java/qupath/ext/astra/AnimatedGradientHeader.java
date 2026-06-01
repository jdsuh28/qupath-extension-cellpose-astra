package qupath.ext.astra;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
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
    private static final double RENDER_SCALE = 4.0d;
    private static final double GRADIENT_SPAN_MULTIPLIER = 3.0d;
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
     * Keeps the canvas layout footprint stable while rendering into a denser
     * backing buffer to avoid visible color banding.
     */
    private void resizeCanvasAndDraw() {
        double width = Math.max(1.0d, getWidth());
        double height = Math.max(1.0d, getHeight());
        double scaledWidth = Math.ceil(width * RENDER_SCALE);
        double scaledHeight = Math.ceil(height * RENDER_SCALE);

        if (canvas.getWidth() != scaledWidth) {
            canvas.setWidth(scaledWidth);
        }
        if (canvas.getHeight() != scaledHeight) {
            canvas.setHeight(scaledHeight);
        }

        canvas.setScaleX(1.0d / RENDER_SCALE);
        canvas.setScaleY(1.0d / RENDER_SCALE);
        canvas.setTranslateX(-0.5d * (scaledWidth - width));
        canvas.setTranslateY(-0.5d * (scaledHeight - height));

        draw(currentPhase(System.nanoTime()));
    }

    /**
     * Paints one frame of the moving gradient.
     *
     * @param phase normalized animation phase.
     */
    private void draw(double phase) {
        double width = Math.max(1.0d, getWidth());
        double height = Math.max(1.0d, getHeight());
        double pixelWidth = Math.max(1.0d, canvas.getWidth());
        double pixelHeight = Math.max(1.0d, canvas.getHeight());
        double span = width * GRADIENT_SPAN_MULTIPLIER;
        double offset = phase * span * 2.0d;
        LinearGradient gradient = new LinearGradient(
                -span + offset,
                0.0d,
                span + offset,
                0.0d,
                false,
                CycleMethod.REPEAT,
                STOPS
        );

        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setTransform(1.0d, 0.0d, 0.0d, 1.0d, 0.0d, 0.0d);
        graphics.clearRect(0.0d, 0.0d, pixelWidth, pixelHeight);
        graphics.scale(RENDER_SCALE, RENDER_SCALE);
        graphics.setFill(gradient);
        graphics.fillRect(0.0d, 0.0d, width, height);
        graphics.setFill(Color.rgb(6, 23, 32, 0.18d));
        graphics.fillRect(0.0d, 0.0d, width, height);
    }
}
