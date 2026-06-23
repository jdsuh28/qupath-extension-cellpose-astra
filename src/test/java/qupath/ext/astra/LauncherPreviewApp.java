package qupath.ext.astra;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.geometry.Bounds;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Development-only launcher preview for visual QA without installing a release.
 */
public final class LauncherPreviewApp extends Application {

    private static final Map<String, String> SCRIPT_PATHS = Map.of(
            "training", "modules/pipelines/cellpose/training/src/main/groovy/training.groovy",
            "tuning", "modules/pipelines/cellpose/tuning/src/main/groovy/tuning.groovy",
            "validation", "modules/pipelines/cellpose/validation/src/main/groovy/validation.groovy",
            "vascular", "modules/pipelines/analysis/vascular/src/main/groovy/vascular.groovy",
            "colocalization", "modules/pipelines/analysis/colocalization/src/main/groovy/colocalization.groovy",
            "oneshot", "modules/tools/sma-af647-oneshot/src/main/groovy/smaAf647Oneshot.groovy",
            "generate-regions", "modules/pipelines/analysis/generate-regions/src/main/groovy/generateRegions.groovy"
    );

    private static PreviewOptions options;

    @Override
    public void start(Stage primaryStage) throws Exception {
        Files.createDirectories(options.userPath());
        Files.createDirectories(options.outputPath());
        PathPrefs.userPathProperty().set(options.userPath().toString());

        QuPathGUI qupath = QuPathGUI.createHiddenInstance();
        Path scriptPath = scriptPath(options.astraRoot(), options.scriptName());
        String script = Files.readString(scriptPath);
        String title = displayTitle(options.scriptName());

        Platform.runLater(() -> PipelineLauncher.configureAndRun(qupath, title, script));
        if (options.snapshots()) {
            scheduleSnapshots(title, options.snapshotMode());
        }
    }

    private static void scheduleSnapshots(String title, String snapshotMode) {
        if ("dashboard".equals(snapshotMode)) {
            schedule(1.5, () -> snapshot("dashboard", title));
            schedule(2.1, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("run-setup".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Run Setup"));
            schedule(2.2, () -> snapshot("run-setup", title));
            schedule(2.8, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("rail-diagnostic".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Models"));
            schedule(2.2, () -> snapshotRailDiagnostic("models-rail-diagnostic", title));
            schedule(2.8, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("models".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Models"));
            schedule(2.2, () -> snapshot("models", title));
            schedule(2.8, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("help-dialog".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Run Setup"));
            schedule(2.6, () -> fireFirstHelpButton(title));
            schedule(3.6, () -> snapshot("help-dialog", title));
            schedule(4.4, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("margin-diagnostic".equals(snapshotMode)) {
            schedule(1.5, () -> snapshotMarginDiagnostic("margin-diagnostic", title));
            schedule(2.1, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("focused-panel-diagnostic".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Run Setup"));
            schedule(2.2, () -> snapshotFocusedPanelDiagnostic("run-setup-focused-panel-diagnostic", title));
            schedule(2.8, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("focused-panel-diagnostic-all".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Run Setup"));
            schedule(2.2, () -> snapshotFocusedPanelDiagnostic("run-setup-focused-panel-diagnostic", title));
            schedule(2.8, () -> fireButton(title, "Back to Dashboard"));
            schedule(3.3, () -> fireButton(title, "Images & Scope"));
            schedule(4.0, () -> snapshotFocusedPanelDiagnostic("images-scope-focused-panel-diagnostic", title));
            schedule(4.6, () -> fireButton(title, "Back to Dashboard"));
            schedule(5.1, () -> fireButton(title, "Models"));
            schedule(5.8, () -> snapshotFocusedPanelDiagnostic("models-focused-panel-diagnostic", title));
            schedule(6.4, () -> fireButton(title, "Back to Dashboard"));
            schedule(6.9, () -> fireButton(title, "Segmentation"));
            schedule(7.6, () -> snapshotFocusedPanelDiagnostic("segmentation-focused-panel-diagnostic", title));
            schedule(8.2, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("settings-menu".equals(snapshotMode)) {
            schedule(1.5, () -> showHeaderMenu(title, "Settings"));
            schedule(2.5, () -> snapshotTransientWindow("settings-menu", title));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("project-menu".equals(snapshotMode)) {
            schedule(1.5, () -> showHeaderMenu(title, "Project"));
            schedule(2.5, () -> snapshotTransientWindow("project-menu", title));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("view-menu".equals(snapshotMode)) {
            schedule(1.5, () -> showHeaderMenu(title, "View"));
            schedule(2.5, () -> snapshotTransientWindow("view-menu", title));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("combo-popup".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Segmentation"));
            schedule(2.2, () -> showFirstComboPopup(title));
            schedule(3.0, () -> snapshotTransientWindow("combo-popup", title));
            schedule(3.5, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("selected-images-dialog".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Images & Scope"));
            schedule(2.2, () -> selectFirstComboValue(title, "PROJECT_IMAGE_SELECTION"));
            schedule(2.9, () -> Platform.runLater(() -> fireButton(title, "Choose Images...")));
            schedule(4.1, () -> snapshotTransientWindow("selected-images-dialog", title));
            schedule(4.7, LauncherPreviewApp::closeAllWindows);
            return;
        }
        schedule(1.5, () -> snapshot("01-dashboard", title));
        schedule(2.2, () -> showHeaderMenu(title, "View"));
        schedule(2.8, () -> hideTransientWindows(title));
        schedule(3.2, () -> showHeaderMenu(title, "Project"));
        schedule(4.2, () -> snapshotTransientWindow("02-project-menu", title));
        schedule(4.5, () -> hideTransientWindows(title));
        schedule(4.9, () -> showHeaderMenu(title, "Settings"));
        schedule(5.9, () -> snapshotTransientWindow("03-settings-menu", title));
        schedule(6.2, () -> hideTransientWindows(title));
        schedule(6.6, () -> showHeaderMenu(title, "View"));
        schedule(7.6, () -> snapshotTransientWindow("04-view-menu", title));
        schedule(7.9, () -> hideTransientWindows(title));
        schedule(8.3, () -> fireButton(title, "Run Setup"));
        schedule(8.9, () -> snapshot("05-run-setup", title));
        schedule(9.5, () -> fireButton(title, "Back to Dashboard"));
        schedule(10.0, () -> fireButton(title, "Images & Scope"));
        schedule(10.6, () -> snapshot("06-images-scope", title));
        schedule(11.2, () -> fireButton(title, "Back to Dashboard"));
        schedule(11.7, () -> fireButton(title, "Models"));
        schedule(12.3, () -> snapshot("07-models", title));
        schedule(12.9, () -> fireButton(title, "Back to Dashboard"));
        schedule(13.4, () -> fireButton(title, "Segmentation"));
        schedule(14.0, () -> snapshot("08-segmentation", title));
        schedule(14.5, () -> hideTransientWindows(title));
        schedule(14.7, () -> showFirstComboPopup(title));
        schedule(15.3, () -> snapshotTransientWindow("09-combo-popup", title));
        schedule(15.6, () -> hideTransientWindows(title));
        schedule(16.0, () -> fireFirstHelpButton(title));
        schedule(16.7, () -> snapshot("10-help-dialog", title));
        schedule(17.5, LauncherPreviewApp::closeAllWindows);
    }

    private static void schedule(double seconds, Runnable runnable) {
        PauseTransition pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(event -> runnable.run());
        pause.play();
    }

    private static Path scriptPath(Path astraRoot, String scriptName) {
        String key = scriptName.toLowerCase(Locale.ROOT);
        String relative = SCRIPT_PATHS.get(key);
        if (relative == null) {
            throw new IllegalArgumentException("Unknown ASTRA preview script: " + scriptName
                    + ". Allowed values: " + SCRIPT_PATHS.keySet());
        }
        Path path = astraRoot.resolve(relative).normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("ASTRA script not found: " + path);
        }
        return path;
    }

    private static String displayTitle(String scriptName) {
        return switch (scriptName.toLowerCase(Locale.ROOT)) {
            case "training" -> "Training";
            case "tuning" -> "Tuning";
            case "validation" -> "Validation";
            case "vascular" -> "Vascular";
            case "colocalization" -> "Colocalization";
            case "oneshot" -> "Marker Rescue";
            case "generate-regions" -> "Generate Regions";
            default -> scriptName;
        };
    }

    private static void fireButton(String title, String text) {
        boolean fired = findWindowRoot(title)
                .flatMap(root -> root.lookupAll(".button").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(button -> text.equals(button.getText()))
                        .findFirst())
                .map(button -> {
                    button.fire();
                    return true;
                })
                .orElse(false);
        if (!fired) {
            clickNodeWithText(title, text);
        }
    }

    private static void fireFirstHelpButton(String title) {
        findWindowRoot(title)
                .flatMap(root -> root.lookupAll(".astra-button-help").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .findFirst())
                .ifPresent(button -> Platform.runLater(button::fire));
    }

    private static void showHeaderMenu(String title, String menuText) {
        boolean shown = findWindowRoot(title)
                .flatMap(root -> root.lookupAll(".astra-header-menu-button").stream()
                        .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> menuText.equals(button.getText()) || nodeContainsText(button, menuText))
                .findFirst())
        .map(button -> {
                    button.fire();
                    return true;
                })
                .orElse(false);
        if (!shown) {
            System.err.println("No header menu button for " + menuText);
        }
    }

    private static void showFirstComboPopup(String title) {
        findWindowRoot(title)
                .flatMap(root -> root.lookupAll(".astra-combo").stream()
                .filter(ComboBox.class::isInstance)
                .map(ComboBox.class::cast)
                .findFirst())
                .ifPresent(ComboBox::show);
    }

    private static void selectFirstComboValue(String title, String value) {
        findWindowRoot(title)
                .flatMap(root -> root.lookupAll(".astra-combo").stream()
                        .filter(ComboBox.class::isInstance)
                        .map(ComboBox.class::cast)
                        .findFirst())
                .ifPresent(combo -> selectComboValue(combo, value));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void selectComboValue(ComboBox combo, String value) {
        for (Object item : combo.getItems()) {
            if (value.equals(String.valueOf(item))) {
                combo.setValue(item);
                return;
            }
        }
        System.err.println("No combo value " + value);
    }

    private static void clickNodeWithText(String title, String text) {
        findWindowRoot(title)
                .flatMap(root -> root.lookupAll(".astra-settings-card").stream()
                        .filter(node -> nodeContainsText(node, text))
                        .findFirst())
                .ifPresent(node -> {
                    if (node instanceof Button button) {
                        button.fire();
                    } else {
                        clickNode(node);
                    }
                });
    }

    private static boolean nodeContainsText(Node node, String text) {
        if (node instanceof Labeled labeled
                && labeled.getText() != null
                && labeled.getText().contains(text)) {
            return true;
        }
        if (node instanceof Labeled labeled
                && labeled.getGraphic() != null
                && nodeContainsText(labeled.getGraphic(), text)) {
            return true;
        }
        if (node instanceof Text textNode
                && textNode.getText() != null
                && textNode.getText().contains(text)) {
            return true;
        }
        if (node instanceof Parent parent) {
            return parent.getChildrenUnmodifiable().stream()
                    .anyMatch(child -> nodeContainsText(child, text));
        }
        return false;
    }

    private static void clickNode(Node node) {
        MouseEvent event = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                node.getLayoutBounds().getWidth() / 2.0d,
                node.getLayoutBounds().getHeight() / 2.0d,
                0.0d,
                0.0d,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                null);
        node.fireEvent(event);
    }

    private static Optional<Node> findWindowRoot(String title) {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> title.equals(windowTitle(window)))
                .map(Window::getScene)
                .<Node>map(Scene::getRoot)
                .findFirst();
    }

    private static String windowTitle(Window window) {
        if (window instanceof Stage stage) {
            return stage.getTitle();
        }
        return "";
    }

    private static void snapshot(String name, String launcherTitle) {
        Window target = Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> "ASTRA Parameter Help".equals(windowTitle(window)))
                .findFirst()
                .orElseGet(() -> Window.getWindows().stream()
                        .filter(Window::isShowing)
                        .filter(window -> launcherTitle.equals(windowTitle(window)))
                        .findFirst()
                        .orElse(null));
        if (target == null || target.getScene() == null) {
            System.err.println("No snapshot target for " + name);
            return;
        }
        Node root = target.getScene().getRoot();
        WritableImage image = root.snapshot(new SnapshotParameters(), null);
        writeImage(name, image);
    }

    private static void snapshotTransientWindow(String name, String launcherTitle) {
        Window target = Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> !launcherTitle.equals(windowTitle(window)))
                .filter(window -> !"ASTRA Parameter Help".equals(windowTitle(window)))
                .filter(window -> window.getScene() != null)
                .reduce((first, second) -> second)
                .orElse(null);
        if (target == null) {
            System.err.println("No transient window for " + name);
            Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .forEach(window -> System.err.println("  visible window "
                            + window.getClass().getName()
                            + " title='" + windowTitle(window) + "'"
                            + " scene=" + (window.getScene() != null)
                            + " x=" + window.getX()
                            + " y=" + window.getY()
                            + " w=" + window.getWidth()
                            + " h=" + window.getHeight()));
            return;
        }
        WritableImage image = target.getScene().getRoot().snapshot(new SnapshotParameters(), null);
        writeImage(name, image);
    }

    private static void writeImage(String name, WritableImage image) {
        File file = options.outputPath().resolve(name + ".png").toFile();
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
            System.out.println(file.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static void snapshotRailDiagnostic(String name, String launcherTitle) {
        Optional<Node> rootOptional = findWindowRoot(launcherTitle);
        if (rootOptional.isEmpty()) {
            System.err.println("No launcher root for " + name);
            return;
        }
        Node root = rootOptional.get();
        WritableImage image = root.snapshot(new SnapshotParameters(), null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        List<RailMarker> markers = railMarkers(root);
        List<RailDistance> distances = railDistances(markers);
        drawRailMarkers(buffered, markers);
        File file = options.outputPath().resolve(name + ".png").toFile();
        try {
            ImageIO.write(buffered, "png", file);
            System.out.println(file.getAbsolutePath());
            markers.forEach(marker -> System.out.printf(
                    Locale.ROOT,
                    "%s x=%.2f%n",
                    marker.label(),
                    marker.x()));
            distances.forEach(distance -> System.out.printf(
                    Locale.ROOT,
                    "%s %.2f px%n",
                    distance.label(),
                    distance.distance()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static void snapshotMarginDiagnostic(String name, String launcherTitle) {
        Optional<Node> rootOptional = findWindowRoot(launcherTitle);
        if (rootOptional.isEmpty()) {
            System.err.println("No launcher root for " + name);
            return;
        }
        Node sceneRoot = rootOptional.get();
        WritableImage image = sceneRoot.snapshot(new SnapshotParameters(), null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        List<DistanceMarker> markers = marginMarkers(sceneRoot);
        drawDistanceMarkers(buffered, markers);
        File file = options.outputPath().resolve(name + ".png").toFile();
        try {
            ImageIO.write(buffered, "png", file);
            System.out.println(file.getAbsolutePath());
            printLayoutDiagnostics(sceneRoot);
            markers.forEach(marker -> System.out.printf(
                    Locale.ROOT,
                    "%s %.2f px%n",
                    marker.label(),
                    marker.distance()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static void snapshotFocusedPanelDiagnostic(String name, String launcherTitle) {
        Optional<Node> rootOptional = findWindowRoot(launcherTitle);
        if (rootOptional.isEmpty()) {
            System.err.println("No launcher root for " + name);
            return;
        }
        Node sceneRoot = rootOptional.get();
        WritableImage image = sceneRoot.snapshot(new SnapshotParameters(), null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        List<DistanceMarker> markers = focusedPanelMarkers(sceneRoot);
        drawDistanceMarkers(buffered, markers);
        File file = options.outputPath().resolve(name + ".png").toFile();
        try {
            ImageIO.write(buffered, "png", file);
            System.out.println(file.getAbsolutePath());
            markers.forEach(marker -> System.out.printf(
                    Locale.ROOT,
                    "%s %.2f px%n",
                    marker.label(),
                    marker.distance()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static void printLayoutDiagnostics(Node sceneRoot) {
        System.out.println("LAYOUT DIAGNOSTIC");
        firstNode(sceneRoot, ".astra-launcher-workspace").ifPresent(workspace -> {
            printNodeMetric(sceneRoot, "workspace", workspace);
            if (workspace instanceof javafx.scene.layout.Pane pane) {
                pane.getChildren().forEach(child -> printNodeMetric(sceneRoot,
                        "workspace child " + child.getStyleClass(), child));
            }
        });
        firstNode(sceneRoot, ".astra-settings-scroll").ifPresent(scroll -> {
            printNodeMetric(sceneRoot, "settings scroll", scroll);
            firstNode(scroll, ".viewport").ifPresent(viewport -> printNodeMetric(sceneRoot, "settings viewport", viewport));
            verticalScrollBar(scroll).ifPresent(bar -> {
                printNodeMetric(sceneRoot, "settings scrollbar gutter", bar);
                visibleScrollbarBar(bar).ifPresent(visual -> printNodeMetric(sceneRoot, "settings scrollbar visual", visual));
            });
        });
        firstNode(sceneRoot, ".astra-output-pane").ifPresent(output -> printNodeMetric(sceneRoot, "output pane", output));
        firstNode(sceneRoot, ".astra-main-action-bar").ifPresent(bar -> printNodeMetric(sceneRoot, "main action bar", bar));
        buttonByText(sceneRoot, "Run").ifPresent(run -> printNodeMetric(sceneRoot, "run button", run));
    }

    private static void printNodeMetric(Node sceneRoot, String label, Node node) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Bounds bounds = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
        System.out.printf(
                Locale.ROOT,
                "%s x=%.2f y=%.2f w=%.2f h=%.2f%n",
                label,
                bounds.getMinX(),
                bounds.getMinY(),
                bounds.getWidth(),
                bounds.getHeight());
    }

    private static List<DistanceMarker> marginMarkers(Node sceneRoot) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Bounds root = relativeBounds(sceneRoot, sceneRoot, rootMinX, rootMinY);
        List<DistanceMarker> markers = new ArrayList<>();
        Optional<Node> launcherRoot = firstNode(sceneRoot, ".astra-launcher-root");
        Optional<Node> header = firstNode(sceneRoot, ".astra-animated-gradient-header");
        Optional<Node> workspace = firstNode(sceneRoot, ".astra-launcher-workspace");
        Optional<Node> inputPane = firstNode(sceneRoot, ".astra-settings-scroll");
        Optional<Node> outputPane = firstNode(sceneRoot, ".astra-output-pane");
        Optional<Node> channelPanel = firstNode(sceneRoot, ".astra-channel-panel");
        Optional<Node> settingsPanel = firstNode(sceneRoot, ".astra-routine-settings-panel");
        Optional<Node> advancedPanel = firstNode(sceneRoot, ".astra-advanced-unlock-panel");
        Optional<Node> buttonBar = firstNode(sceneRoot, ".button-bar");
        Optional<Node> cancel = buttonByText(sceneRoot, "Cancel");
        Optional<Node> run = buttonByText(sceneRoot, "Run");

        header.ifPresent(node -> {
            Bounds b = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
            markers.add(vertical("root top -> header", root.getMinY(), b.getMinY(), root.getMinX() + 18));
        });
        if (header.isPresent() && workspace.isPresent()) {
            Bounds a = relativeBounds(sceneRoot, header.get(), rootMinX, rootMinY);
            Bounds b = relativeBounds(sceneRoot, workspace.get(), rootMinX, rootMinY);
            markers.add(vertical("header -> workspace", a.getMaxY(), b.getMinY(), root.getMinX() + 42));
        }
        if (workspace.isPresent() && inputPane.isPresent()) {
            Bounds w = relativeBounds(sceneRoot, workspace.get(), rootMinX, rootMinY);
            Bounds i = relativeBounds(sceneRoot, inputPane.get(), rootMinX, rootMinY);
            markers.add(horizontal("root left -> input", root.getMinX(), i.getMinX(), i.getMinY() + 18));
            markers.add(vertical("workspace top -> input", w.getMinY(), i.getMinY(), i.getMinX() + 18));
        }
        if (inputPane.isPresent()) {
            Node scroll = inputPane.get();
            Bounds input = relativeBounds(sceneRoot, scroll, rootMinX, rootMinY);
            firstNode(scroll, ".viewport").ifPresent(viewport -> {
                Bounds viewportBounds = relativeBounds(sceneRoot, viewport, rootMinX, rootMinY);
                markers.add(horizontal("input content -> bar lane", viewportBounds.getMaxX(), input.getMaxX(), input.getMinY() + 42));
            });
            verticalScrollBar(scroll).ifPresent(bar -> {
                Bounds gutterBounds = relativeBounds(sceneRoot, bar, rootMinX, rootMinY);
                Node visualBar = visibleScrollbarBar(bar).orElse(bar);
                Bounds barBounds = relativeBounds(sceneRoot, visualBar, rootMinX, rootMinY);
                inputContentRightEdge(scroll, sceneRoot, rootMinX, rootMinY).ifPresent(contentRight ->
                        markers.add(horizontal("input panels -> bar", contentRight, barBounds.getMinX(), input.getMinY() + 66)));
                firstNode(scroll, ".viewport").ifPresent(viewport -> {
                    Bounds viewportBounds = relativeBounds(sceneRoot, viewport, rootMinX, rootMinY);
                    markers.add(horizontal("viewport -> bar", viewportBounds.getMaxX(), barBounds.getMinX(), input.getMinY() + 78));
                });
                if (outputPane.isPresent()) {
                    Bounds output = relativeBounds(sceneRoot, outputPane.get(), rootMinX, rootMinY);
                    markers.add(horizontal("bar -> output", barBounds.getMaxX(), output.getMinX(), input.getMinY() + 90));
                    markers.add(horizontal("gutter -> output", gutterBounds.getMaxX(), output.getMinX(), input.getMinY() + 102));
                }
            });
        }
        if (inputPane.isPresent() && outputPane.isPresent()) {
            Bounds input = relativeBounds(sceneRoot, inputPane.get(), rootMinX, rootMinY);
            Bounds output = relativeBounds(sceneRoot, outputPane.get(), rootMinX, rootMinY);
            markers.add(horizontal("input pane -> output pane", input.getMaxX(), output.getMinX(), input.getMinY() + 114));
        }
        outputPane.ifPresent(node -> {
            Bounds output = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
            markers.add(horizontal("output -> root right", output.getMaxX(), root.getMaxX(), output.getMinY() + 18));
            workspace.ifPresent(workspaceNode -> {
                Bounds workspaceBounds = relativeBounds(sceneRoot, workspaceNode, rootMinX, rootMinY);
                markers.add(horizontal("output -> workspace right", output.getMaxX(), workspaceBounds.getMaxX(), output.getMinY() + 42));
            });
        });
        if (channelPanel.isPresent() && settingsPanel.isPresent()) {
            Bounds a = relativeBounds(sceneRoot, channelPanel.get(), rootMinX, rootMinY);
            Bounds b = relativeBounds(sceneRoot, settingsPanel.get(), rootMinX, rootMinY);
            markers.add(vertical("channel -> settings", a.getMaxY(), b.getMinY(), a.getMinX() + 20));
        }
        if (settingsPanel.isPresent() && advancedPanel.isPresent()) {
            Bounds a = relativeBounds(sceneRoot, settingsPanel.get(), rootMinX, rootMinY);
            Bounds b = relativeBounds(sceneRoot, advancedPanel.get(), rootMinX, rootMinY);
            markers.add(vertical("settings -> advanced", a.getMaxY(), b.getMinY(), a.getMinX() + 44));
        }
        if (outputPane.isPresent() && run.isPresent()) {
            Bounds output = relativeBounds(sceneRoot, outputPane.get(), rootMinX, rootMinY);
            Bounds r = relativeBounds(sceneRoot, run.get(), rootMinX, rootMinY);
            markers.add(vertical("output pane -> run", output.getMaxY(), r.getMinY(), r.getMaxX() - 58));
        }
        if (run.isPresent()) {
            Bounds r = relativeBounds(sceneRoot, run.get(), rootMinX, rootMinY);
            markers.add(horizontal("run -> root right", r.getMaxX(), root.getMaxX(), r.getMinY() + 8));
            markers.add(vertical("run -> root bottom", r.getMaxY(), root.getMaxY(), r.getMaxX() - 16));
        }
        if (cancel.isPresent() && run.isPresent()) {
            Bounds c = relativeBounds(sceneRoot, cancel.get(), rootMinX, rootMinY);
            Bounds r = relativeBounds(sceneRoot, run.get(), rootMinX, rootMinY);
            markers.add(horizontal("cancel -> run", c.getMaxX(), r.getMinX(), c.getMinY() + 8));
        }
        return markers;
    }

    private static List<DistanceMarker> focusedPanelMarkers(Node sceneRoot) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        List<DistanceMarker> markers = new ArrayList<>();
        Optional<Node> panelNode = firstNode(sceneRoot, ".astra-routine-settings-panel");
        Optional<Node> headingRowNode = firstNode(sceneRoot, ".astra-section-heading-row");
        Optional<Node> settingsHostNode = firstNode(sceneRoot, ".astra-settings-host");
        Optional<Node> headerNode = firstNode(sceneRoot, ".astra-focused-section-header");
        Optional<Node> contentNode = firstNode(sceneRoot, ".astra-section-content-focused");
        if (panelNode.isEmpty()) {
            return markers;
        }
        Bounds panel = relativeBounds(sceneRoot, panelNode.get(), rootMinX, rootMinY);
        headerNode.ifPresent(node -> {
            Bounds header = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
            markers.add(horizontal("panel left -> focused header",
                    panel.getMinX(), header.getMinX(), header.getMinY() + 16));
            markers.add(horizontal("focused header -> panel right",
                    header.getMaxX(), panel.getMaxX(), header.getMinY() + 30));
            settingsHostNode.ifPresent(hostNode -> {
                Bounds host = relativeBounds(sceneRoot, hostNode, rootMinX, rootMinY);
                markers.add(vertical("panel top -> settings host",
                        panel.getMinY(), host.getMinY(), host.getMinX() + 20));
            });
            headingRowNode.ifPresent(headingNode -> {
                Bounds heading = relativeBounds(sceneRoot, headingNode, rootMinX, rootMinY);
                markers.add(vertical("heading row -> focused header",
                        heading.getMaxY(), header.getMinY(), header.getMinX() + 20));
            });
        });
        if (headerNode.isPresent() && contentNode.isPresent()) {
            Bounds header = relativeBounds(sceneRoot, headerNode.get(), rootMinX, rootMinY);
            Bounds content = relativeBounds(sceneRoot, contentNode.get(), rootMinX, rootMinY);
            markers.add(vertical("focused header -> parameter grid",
                    header.getMaxY(), content.getMinY(), content.getMinX() + 20));
        }
        contentNode.ifPresent(node -> {
            Bounds content = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
            markers.add(horizontal("panel left -> parameter grid",
                    panel.getMinX(), content.getMinX(), content.getMinY() + 18));
            markers.add(horizontal("parameter grid -> panel right",
                    content.getMaxX(), panel.getMaxX(), content.getMinY() + 32));
            markers.add(vertical("parameter grid -> panel bottom",
                    content.getMaxY(), panel.getMaxY(), content.getMinX() + 44));
            firstNode(node, ".astra-parameter-row").ifPresent(rowNode -> {
                Bounds row = relativeBounds(sceneRoot, rowNode, rootMinX, rootMinY);
                markers.add(horizontal("parameter grid -> first row",
                        content.getMinX(), row.getMinX(), row.getMinY() + 12));
            });
            firstNode(node, ".astra-parameter-editor").ifPresent(editorNode -> {
                Bounds editor = relativeBounds(sceneRoot, editorNode, rootMinX, rootMinY);
                markers.add(horizontal("first editor -> parameter grid",
                        editor.getMaxX(), content.getMaxX(), editor.getMinY() + 12));
            });
        });
        return markers;
    }

    private static List<RailDistance> railDistances(List<RailMarker> markers) {
        Map<String, Double> x = new HashMap<>();
        markers.forEach(marker -> x.put(marker.label(), marker.x()));
        List<RailDistance> distances = new ArrayList<>();
        addRailDistance(distances, x,
                "independent edge -> bar",
                "independent box left",
                "independent bar left");
        addRailDistance(distances, x,
                "independent row -> bar",
                "independent row left",
                "independent bar left");
        addRailDistance(distances, x,
                "independent bar -> label",
                "independent bar right",
                "independent label");
        addRailDistance(distances, x,
                "dependent edge -> bar",
                "dependent panel left",
                "bar left");
        addRailDistance(distances, x,
                "dependent bar -> label",
                "bar right",
                "dependent row label");
        addRailDistance(distances, x,
                "independent label -> dependent title",
                "independent label",
                "dependent title");
        return distances;
    }

    private static void addRailDistance(List<RailDistance> distances,
                                        Map<String, Double> x,
                                        String label,
                                        String startLabel,
                                        String endLabel) {
        Double start = x.get(startLabel);
        Double end = x.get(endLabel);
        if (start != null && end != null) {
            distances.add(new RailDistance(label, Math.abs(end - start)));
        }
    }

    private static List<RailMarker> railMarkers(Node root) {
        double rootMinX = root.localToScene(root.getBoundsInLocal()).getMinX();
        List<RailMarker> markers = new ArrayList<>();
        findFirst(root, ".astra-parameter-label", "Nucleus Model Source")
                .ifPresent(node -> {
                    firstNode(root, ".astra-section-content-focused")
                            .ifPresent(content -> markers.add(new RailMarker(
                                    "independent box left",
                                    content.localToScene(content.getLayoutBounds()).getMinX() - rootMinX,
                                    new Color(188, 71, 73))));
                    markers.add(new RailMarker(
                            "independent row left",
                            node.getParent().localToScene(node.getParent().getBoundsInLocal()).getMinX() - rootMinX,
                            new Color(120, 0, 160)));
                    node.getParent().lookupAll(".astra-parameter-anchor").stream()
                            .findFirst()
                            .ifPresent(anchor -> {
                                markers.add(new RailMarker(
                                        "independent bar left",
                                        anchor.localToScene(anchor.getBoundsInLocal()).getMinX() - rootMinX,
                                        new Color(0, 95, 115)));
                                markers.add(new RailMarker(
                                        "independent bar right",
                                        anchor.localToScene(anchor.getBoundsInLocal()).getMaxX() - rootMinX,
                                        new Color(10, 147, 150)));
                            });
                    markers.add(new RailMarker(
                            "independent label",
                            textOrNodeMinX(node) - rootMinX,
                            new Color(230, 57, 70)));
                });
        findFirst(root, ".astra-dependent-panel-title", "Nucleus model-source controls")
                .ifPresent(node -> markers.add(new RailMarker(
                        "dependent title",
                        textOrNodeMinX(node) - rootMinX,
                        new Color(255, 159, 28))));
        firstNode(root, ".astra-dependent-panel-nucleus-model-source")
                .ifPresent(panel -> {
                    markers.add(new RailMarker(
                            "dependent panel left",
                            panel.localToScene(panel.getBoundsInLocal()).getMinX() - rootMinX,
                            new Color(42, 157, 143)));
                    panel.lookupAll(".astra-parameter-anchor").stream()
                            .findFirst()
                            .ifPresent(anchor -> {
                                markers.add(new RailMarker(
                                        "bar left",
                                        anchor.localToScene(anchor.getBoundsInLocal()).getMinX() - rootMinX,
                                        new Color(69, 123, 157)));
                                markers.add(new RailMarker(
                                        "bar right",
                                        anchor.localToScene(anchor.getBoundsInLocal()).getMaxX() - rootMinX,
                                        new Color(29, 53, 87)));
                            });
                    findFirst(panel, ".astra-parameter-label", "Nucleus Built-In Model")
                            .ifPresent(node -> markers.add(new RailMarker(
                                    "dependent row label",
                                    textOrNodeMinX(node) - rootMinX,
                                    new Color(131, 56, 236))));
                    panel.lookupAll(".astra-button-help").stream()
                            .findFirst()
                            .ifPresent(help -> markers.add(new RailMarker(
                                    "dependent help",
                                    help.localToScene(help.getBoundsInLocal()).getMinX() - rootMinX,
                                    new Color(255, 0, 110))));
                });
        return markers;
    }

    private static void drawRailMarkers(BufferedImage image, List<RailMarker> markers) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            int labelY = 18;
            for (RailMarker marker : markers) {
                int x = (int)Math.round(marker.x());
                g.setColor(marker.color());
                g.setStroke(new BasicStroke(2.0f));
                g.drawLine(x, 0, x, image.getHeight());
                g.setColor(new Color(255, 255, 255, 235));
                int textWidth = g.getFontMetrics().stringWidth(marker.label());
                g.fillRoundRect(x + 4, labelY - 12, textWidth + 8, 18, 8, 8);
                g.setColor(marker.color());
                g.drawString(marker.label(), x + 8, labelY + 1);
                labelY += 20;
            }
        } finally {
            g.dispose();
        }
    }

    private static void drawDistanceMarkers(BufferedImage image, List<DistanceMarker> markers) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            int index = 0;
            for (DistanceMarker marker : markers) {
                Color color = diagnosticColor(index++);
                g.setColor(color);
                g.setStroke(new BasicStroke(2.0f));
                if (marker.horizontal()) {
                    drawHorizontalDistance(g, marker, color);
                } else {
                    drawVerticalDistance(g, marker, color);
                }
            }
        } finally {
            g.dispose();
        }
    }

    private static void drawHorizontalDistance(Graphics2D g, DistanceMarker marker, Color color) {
        int x1 = (int)Math.round(marker.start());
        int x2 = (int)Math.round(marker.end());
        int y = (int)Math.round(marker.cross());
        g.drawLine(x1, y, x2, y);
        g.drawLine(x1, y - 5, x1, y + 5);
        g.drawLine(x2, y - 5, x2, y + 5);
        String label = marker.label() + " " + Math.round(marker.distance()) + "px";
        drawLabel(g, label, Math.min(x1, x2) + 4, y - 8, color);
    }

    private static void drawVerticalDistance(Graphics2D g, DistanceMarker marker, Color color) {
        int y1 = (int)Math.round(marker.start());
        int y2 = (int)Math.round(marker.end());
        int x = (int)Math.round(marker.cross());
        g.drawLine(x, y1, x, y2);
        g.drawLine(x - 5, y1, x + 5, y1);
        g.drawLine(x - 5, y2, x + 5, y2);
        String label = marker.label() + " " + Math.round(marker.distance()) + "px";
        drawLabel(g, label, x + 6, Math.min(y1, y2) + 14, color);
    }

    private static void drawLabel(Graphics2D g, String label, int x, int y, Color color) {
        int width = g.getFontMetrics().stringWidth(label);
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect(x - 3, y - 12, width + 8, 18, 8, 8);
        g.setColor(color);
        g.drawString(label, x + 1, y + 1);
    }

    private static Color diagnosticColor(int index) {
        Color[] colors = {
                new Color(230, 57, 70),
                new Color(42, 157, 143),
                new Color(69, 123, 157),
                new Color(255, 159, 28),
                new Color(131, 56, 236),
                new Color(0, 95, 115),
                new Color(255, 0, 110),
                new Color(88, 129, 87)
        };
        return colors[index % colors.length];
    }

    private static Optional<Node> firstNode(Node root, String styleClass) {
        return root.lookupAll(styleClass).stream().findFirst();
    }

    private static Optional<Node> nthNode(Node root, String styleClass, int index) {
        List<Node> nodes = root.lookupAll(styleClass).stream().toList();
        return index < nodes.size() ? Optional.of(nodes.get(index)) : Optional.empty();
    }

    private static Optional<Node> buttonByText(Node root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .map(Node.class::cast)
                .findFirst();
    }

    private static Optional<Node> verticalScrollBar(Node root) {
        return root.lookupAll(".scroll-bar").stream()
                .filter(node -> node instanceof ScrollBar
                        || node.getStyleClass().contains("scroll-bar"))
                .filter(node -> {
                    Bounds bounds = node.getBoundsInParent();
                    return bounds.getHeight() > bounds.getWidth();
                })
                .findFirst();
    }

    private static Optional<Node> visibleScrollbarBar(Node scrollBar) {
        return firstNode(scrollBar, ".track").or(() -> firstNode(scrollBar, ".thumb"));
    }

    private static Optional<Double> inputContentRightEdge(Node scroll,
                                                          Node sceneRoot,
                                                          double rootMinX,
                                                          double rootMinY) {
        if (!(scroll instanceof ScrollPane scrollPane) || scrollPane.getContent() == null) {
            return Optional.empty();
        }
        Node content = scrollPane.getContent();
        return content.lookupAll(".astra-section-shell").stream()
                .filter(Node::isManaged)
                .map(node -> relativeBounds(sceneRoot, node, rootMinX, rootMinY).getMaxX())
                .max(Double::compareTo);
    }

    private static Bounds relativeBounds(Node sceneRoot, Node node, double rootMinX, double rootMinY) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        return new javafx.geometry.BoundingBox(
                bounds.getMinX() - rootMinX,
                bounds.getMinY() - rootMinY,
                bounds.getWidth(),
                bounds.getHeight());
    }

    private static DistanceMarker horizontal(String label, double start, double end, double y) {
        return new DistanceMarker(label, true, start, end, y);
    }

    private static DistanceMarker vertical(String label, double start, double end, double x) {
        return new DistanceMarker(label, false, start, end, x);
    }

    private static Optional<Node> findFirst(Node root, String styleClass, String text) {
        return root.lookupAll(styleClass).stream()
                .filter(node -> exactNodeText(node, text))
                .findFirst();
    }

    private static boolean exactNodeText(Node node, String text) {
        if (node instanceof Labeled labeled) {
            return text.equals(labeled.getText());
        }
        if (node instanceof Text textNode) {
            return text.equals(textNode.getText());
        }
        return false;
    }

    private static double textOrNodeMinX(Node node) {
        if (node instanceof Text textNode) {
            return textNode.localToScene(textNode.getBoundsInLocal()).getMinX();
        }
        return node.lookupAll(".text").stream()
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .map(text -> text.localToScene(text.getBoundsInLocal()).getMinX())
                .findFirst()
                .orElseGet(() -> node.localToScene(node.getBoundsInLocal()).getMinX());
    }

    private record RailMarker(String label, double x, Color color) {
    }

    private record RailDistance(String label, double distance) {
    }

    private record DistanceMarker(String label, boolean horizontal,
                                  double start, double end, double cross) {
        private double distance() {
            return Math.abs(end - start);
        }
    }

    private static void hideTransientWindows(String launcherTitle) {
        List.copyOf(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .filter(window -> !launcherTitle.equals(windowTitle(window)))
                .filter(window -> !"ASTRA Parameter Help".equals(windowTitle(window)))
                .forEach(Window::hide);
    }

    private static void closeAllWindows() {
        List.copyOf(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .forEach(Stage::close);
        Platform.exit();
        System.exit(0);
    }

    public static void main(String[] args) {
        options = PreviewOptions.parse(args);
        launch(args);
    }

    private record PreviewOptions(
            Path astraRoot,
            String scriptName,
            Path outputPath,
            Path userPath,
            boolean snapshots,
            String snapshotMode
    ) {

        private static PreviewOptions parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            boolean snapshots = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--snapshots".equals(arg)) {
                    snapshots = true;
                    continue;
                }
                if (!arg.startsWith("--") || i + 1 >= args.length) {
                    throw new IllegalArgumentException("Invalid preview argument: " + arg);
                }
                values.put(arg.substring(2), args[++i]);
            }

            Path astraRoot = Path.of(values.getOrDefault("astra-root", "../astra")).normalize();
            String script = values.getOrDefault("script", "vascular");
            Path output = Path.of(values.getOrDefault("output", "/private/tmp/astra-gui-snapshots"));
            Path userPath = Path.of(values.getOrDefault("user-path", "/private/tmp/astra-qupath-dev-user"));
            String snapshotMode = values.getOrDefault("snapshot-mode", "all")
                    .toLowerCase(Locale.ROOT);
            return new PreviewOptions(astraRoot, script, output, userPath, snapshots, snapshotMode);
        }
    }
}
