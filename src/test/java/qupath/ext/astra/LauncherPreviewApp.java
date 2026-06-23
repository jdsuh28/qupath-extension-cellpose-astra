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
import javafx.scene.control.TextField;
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
import java.io.IOException;
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
        if ("geometry-overlay".equals(snapshotMode)) {
            schedule(1.5, () -> snapshotGeometryOverlay("dashboard-geometry-overlay", title));
            schedule(2.1, () -> fireButton(title, "Run Setup"));
            schedule(2.8, () -> snapshotGeometryOverlay("run-setup-geometry-overlay", title));
            schedule(3.4, () -> fireButton(title, "Back to Dashboard"));
            schedule(4.0, () -> fireButton(title, "Images & Scope"));
            schedule(4.7, () -> snapshotGeometryOverlay("images-scope-geometry-overlay", title));
            schedule(5.3, () -> fireButton(title, "Back to Dashboard"));
            schedule(5.9, () -> fireButton(title, "Models"));
            schedule(6.6, () -> snapshotGeometryOverlay("models-geometry-overlay", title));
            schedule(7.2, () -> fireButton(title, "Back to Dashboard"));
            schedule(7.8, () -> fireButton(title, "Segmentation"));
            schedule(8.5, () -> snapshotGeometryOverlay("segmentation-geometry-overlay", title));
            schedule(9.1, () -> fireFirstHelpButton(title));
            schedule(10.1, () -> snapshotGeometryOverlay("help-dialog-geometry-overlay", "ASTRA Parameter Help"));
            schedule(10.8, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("header-menus-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> snapshotSurfaceGeometry("header-action-rail-geometry", title, Surface.HEADER, false));
            schedule(2.1, () -> showHeaderMenu(title, "Settings"));
            schedule(2.8, () -> snapshotSurfaceGeometry("settings-menu-geometry", title, Surface.HEADER_MENU, true));
            schedule(3.1, () -> hideTransientWindows(title));
            schedule(3.5, () -> showHeaderMenu(title, "Project"));
            schedule(4.2, () -> snapshotSurfaceGeometry("project-menu-geometry", title, Surface.HEADER_MENU, true));
            schedule(4.5, () -> hideTransientWindows(title));
            schedule(4.9, () -> showHeaderMenu(title, "View"));
            schedule(5.6, () -> snapshotSurfaceGeometry("view-menu-geometry", title, Surface.HEADER_MENU, true));
            schedule(6.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("combo-popup-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Segmentation"));
            schedule(2.1, () -> snapshotSurfaceGeometry("closed-combo-geometry", title, Surface.COMBO_CLOSED, false));
            schedule(2.5, () -> showFirstComboPopup(title));
            schedule(3.2, () -> snapshotSurfaceGeometry("combo-popup-geometry", title, Surface.COMBO_POPUP, true));
            schedule(3.8, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("output-pane-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> snapshotSurfaceGeometry("output-pane-geometry", title, Surface.OUTPUT, false));
            schedule(2.1, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("all-settings-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "All Settings"));
            schedule(2.2, () -> snapshotSurfaceGeometry("all-settings-geometry", title, Surface.ALL_SETTINGS, false));
            schedule(2.8, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("advanced-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> unlockAdvanced(title));
            schedule(2.4, () -> snapshotSurfaceGeometry("advanced-geometry", title, Surface.ADVANCED, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("custom-controls-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Images & Scope"));
            schedule(2.2, () -> selectFirstComboValue(title, "PROJECT_IMAGE_SELECTION"));
            schedule(2.9, () -> snapshotSurfaceGeometry("custom-controls-geometry", title, Surface.CUSTOM_CONTROLS, false));
            schedule(3.5, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("dialogs-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Images & Scope"));
            schedule(2.2, () -> selectFirstComboValue(title, "PROJECT_IMAGE_SELECTION"));
            schedule(2.9, () -> Platform.runLater(() -> fireButton(title, "Choose Images...")));
            schedule(4.1, () -> snapshotSurfaceGeometry("selected-images-dialog-geometry", title, Surface.DIALOG, true));
            schedule(4.7, LauncherPreviewApp::closeAllWindows);
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

    private static void unlockAdvanced(String title) {
        Optional<Node> rootOptional = findWindowRoot(title);
        if (rootOptional.isEmpty()) {
            System.err.println("No launcher root to unlock advanced controls");
            return;
        }
        Node root = rootOptional.get();
        root.lookupAll(".astra-input").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> "Unlock phrase".equals(field.getPromptText()))
                .findFirst()
                .ifPresent(field -> {
                    field.setText(GuiPresentation.advancedUnlockPhrase());
                    field.fireEvent(new javafx.event.ActionEvent(field, field));
                });
        root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> "Unlock advanced".equals(button.getText()))
                .findFirst()
                .ifPresent(Button::fire);
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

    private static void snapshotGeometryOverlay(String name, String launcherTitle) {
        Optional<Node> rootOptional = findWindowRoot(launcherTitle);
        if (rootOptional.isEmpty()) {
            System.err.println("No launcher root for " + name);
            return;
        }
        Node sceneRoot = rootOptional.get();
        WritableImage image = sceneRoot.snapshot(new SnapshotParameters(), null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        List<DistanceMarker> distances = new ArrayList<>();
        distances.addAll(marginMarkers(sceneRoot));
        distances.addAll(focusedPanelMarkers(sceneRoot));
        List<RailMarker> rails = railMarkers(sceneRoot);
        List<BoundsMarker> bounds = geometryBounds(sceneRoot);
        List<GeometryMeasurement> measurements = geometryMeasurements(sceneRoot);
        drawBoundsMarkers(buffered, bounds);
        drawDistanceMarkers(buffered, distances);
        drawRailMarkers(buffered, rails);
        File file = options.outputPath().resolve(name + ".png").toFile();
        try {
            ImageIO.write(buffered, "png", file);
            writeGeometryTables(name, measurements);
            System.out.println(file.getAbsolutePath());
            measurements.forEach(measurement -> System.out.printf(
                    Locale.ROOT,
                    "%s expected=%.2f observed=%.2f delta=%.2f formula=%s%n",
                    measurement.label(),
                    measurement.expected(),
                    measurement.observed(),
                    measurement.delta(),
                    measurement.formula()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static void snapshotSurfaceGeometry(String name,
                                                String launcherTitle,
                                                Surface surface,
                                                boolean transientWindow) {
        Optional<Node> rootOptional = transientWindow
                ? findTransientWindowRoot(launcherTitle)
                : findWindowRoot(launcherTitle);
        if (rootOptional.isEmpty()) {
            System.err.println("No " + surface + " root for " + name);
            return;
        }
        Node sceneRoot = rootOptional.get();
        WritableImage image = sceneRoot.snapshot(new SnapshotParameters(), null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        List<DistanceMarker> distances = new ArrayList<>();
        List<RailMarker> rails = new ArrayList<>();
        List<BoundsMarker> bounds = surfaceBounds(sceneRoot, surface);
        List<GeometryMeasurement> measurements = surfaceMeasurements(sceneRoot, surface);
        if (surface == Surface.ALL_SETTINGS || surface == Surface.ADVANCED
                || surface == Surface.CUSTOM_CONTROLS) {
            distances.addAll(focusedPanelMarkers(sceneRoot));
            rails.addAll(railMarkers(sceneRoot));
        }
        drawBoundsMarkers(buffered, bounds);
        drawDistanceMarkers(buffered, distances);
        drawRailMarkers(buffered, rails);
        File file = options.outputPath().resolve(name + ".png").toFile();
        try {
            ImageIO.write(buffered, "png", file);
            writeGeometryTables(name, measurements);
            System.out.println(file.getAbsolutePath());
            measurements.forEach(measurement -> System.out.printf(
                    Locale.ROOT,
                    "%s expected=%.2f observed=%.2f delta=%.2f formula=%s%n",
                    measurement.label(),
                    measurement.expected(),
                    measurement.observed(),
                    measurement.delta(),
                    measurement.formula()));
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    private static Optional<Node> findTransientWindowRoot(String launcherTitle) {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> !launcherTitle.equals(windowTitle(window)))
                .filter(window -> !"ASTRA Parameter Help".equals(windowTitle(window)))
                .filter(window -> window.getScene() != null)
                .reduce((first, second) -> second)
                .map(Window::getScene)
                .map(Scene::getRoot);
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

    private static List<BoundsMarker> geometryBounds(Node sceneRoot) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        List<BoundsMarker> bounds = new ArrayList<>();
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "input pane", ".astra-settings-scroll",
                new Color(0, 95, 115));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "scrollbar gutter", ".scroll-bar",
                new Color(255, 159, 28));
        firstNode(sceneRoot, ".astra-settings-scroll")
                .flatMap(LauncherPreviewApp::verticalScrollBar)
                .flatMap(LauncherPreviewApp::visibleScrollbarBar)
                .ifPresent(node -> bounds.add(new BoundsMarker(
                        "visible scrollbar bar",
                        relativeBounds(sceneRoot, node, rootMinX, rootMinY),
                        new Color(255, 0, 110))));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "output pane", ".astra-output-pane",
                new Color(42, 157, 143));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "bottom action bar", ".astra-main-action-bar",
                new Color(131, 56, 236));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "focused panel", ".astra-routine-settings-panel",
                new Color(88, 129, 87));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "parameter grid", ".astra-section-content-focused",
                new Color(230, 57, 70));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "dependent panel", ".astra-dependent-panel",
                new Color(69, 123, 157));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "help dialog content", ".astra-help-dialog-content",
                new Color(88, 129, 87));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "help summary", ".astra-help-summary-grid",
                new Color(230, 57, 70));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "help details shell", ".astra-help-details-shell",
                new Color(42, 157, 143));
        addBounds(bounds, sceneRoot, rootMinX, rootMinY, "help detail card", ".astra-help-detail-card",
                new Color(131, 56, 236));
        return bounds;
    }

    private static void addBounds(List<BoundsMarker> markers,
                                  Node sceneRoot,
                                  double rootMinX,
                                  double rootMinY,
                                  String label,
                                  String styleClass,
                                  Color color) {
        firstNode(sceneRoot, styleClass)
                .ifPresent(node -> markers.add(new BoundsMarker(
                        label,
                        relativeBounds(sceneRoot, node, rootMinX, rootMinY),
                        color)));
    }

    private static List<BoundsMarker> surfaceBounds(Node sceneRoot, Surface surface) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        List<BoundsMarker> bounds = new ArrayList<>();
        switch (surface) {
            case HEADER -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "header action rail",
                        ".astra-header-action-rail", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "header rail tab",
                        ".astra-header-action-rail-tab", new Color(255, 159, 28));
                sceneRoot.lookupAll(".astra-header-menu-button").forEach(node -> bounds.add(new BoundsMarker(
                        "header menu button",
                        relativeBounds(sceneRoot, node, rootMinX, rootMinY),
                        new Color(42, 157, 143))));
            }
            case HEADER_MENU -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "header context menu",
                        ".astra-header-context-menu", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "header options panel",
                        ".astra-header-options-panel", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "header options group",
                        ".astra-header-options-group", new Color(255, 159, 28));
                sceneRoot.lookupAll(".astra-header-segment-button").forEach(node -> bounds.add(new BoundsMarker(
                        "segment button",
                        relativeBounds(sceneRoot, node, rootMinX, rootMinY),
                        new Color(131, 56, 236))));
            }
            case COMBO_CLOSED -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "combo box",
                        ".astra-combo", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "combo cell",
                        ".astra-combo-cell", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "combo arrow button",
                        ".astra-combo-arrow-button", new Color(255, 159, 28));
            }
            case COMBO_POPUP -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "combo popup list",
                        ".list-view", new Color(0, 95, 115));
                sceneRoot.lookupAll(".astra-combo-cell").forEach(node -> bounds.add(new BoundsMarker(
                        "combo popup cell",
                        relativeBounds(sceneRoot, node, rootMinX, rootMinY),
                        new Color(42, 157, 143))));
            }
            case OUTPUT -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "output pane",
                        ".astra-output-pane", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "log status card",
                        ".astra-log-status-card", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "log scroll",
                        ".astra-log-scroll", new Color(255, 159, 28));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "log copy button",
                        ".astra-log-copy-button", new Color(131, 56, 236));
            }
            case ALL_SETTINGS, ADVANCED, CUSTOM_CONTROLS -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "settings panel",
                        ".astra-routine-settings-panel", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "section content",
                        ".astra-section-content", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "focused section content",
                        ".astra-section-content-focused", new Color(255, 159, 28));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "dependent panel",
                        ".astra-dependent-panel", new Color(131, 56, 236));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "advanced settings panel",
                        ".astra-advanced-settings-panel", new Color(88, 129, 87));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "custom nested panel",
                        ".astra-nested-panel", new Color(230, 57, 70));
            }
            case DIALOG -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "dialog pane",
                        ".dialog-pane", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "dialog content",
                        ".content", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "list view",
                        ".astra-list-view", new Color(255, 159, 28));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "dialog input",
                        ".astra-input", new Color(131, 56, 236));
            }
        }
        return bounds;
    }

    private static List<GeometryMeasurement> surfaceMeasurements(Node sceneRoot, Surface surface) {
        List<GeometryMeasurement> measurements = new ArrayList<>();
        switch (surface) {
            case HEADER -> addHeaderMeasurements(sceneRoot, measurements);
            case HEADER_MENU -> addHeaderMenuMeasurements(sceneRoot, measurements);
            case COMBO_CLOSED -> addClosedComboMeasurements(sceneRoot, measurements);
            case COMBO_POPUP -> addComboPopupMeasurements(sceneRoot, measurements);
            case OUTPUT -> addOutputMeasurements(sceneRoot, measurements);
            case ALL_SETTINGS -> addAllSettingsMeasurements(sceneRoot, measurements);
            case ADVANCED -> addAdvancedMeasurements(sceneRoot, measurements);
            case CUSTOM_CONTROLS -> addCustomControlMeasurements(sceneRoot, measurements);
            case DIALOG -> addDialogMeasurements(sceneRoot, measurements);
        }
        return measurements;
    }

    private static void addHeaderMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> rail = firstNode(sceneRoot, ".astra-header-action-rail");
        Optional<Node> tab = firstNode(sceneRoot, ".astra-header-action-rail-tab");
        List<Node> buttons = managedNodes(sceneRoot, ".astra-header-menu-button");
        if (rail.isPresent() && tab.isPresent()) {
            Bounds railBounds = relativeBounds(sceneRoot, rail.get(), rootMinX, rootMinY);
            Bounds tabBounds = relativeBounds(sceneRoot, tab.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "header tab left inset",
                    0.0,
                    tabBounds.getMinX() - railBounds.getMinX(),
                    "tab shares action rail left edge");
            addMeasurement(measurements, "header tab top join",
                    0.0,
                    tabBounds.getMinY() - railBounds.getMinY(),
                    "tab joins action rail top edge");
        }
        if (buttons.size() >= 2) {
            Bounds first = relativeBounds(sceneRoot, buttons.get(0), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, buttons.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "header menu button gap",
                    nestedStaticField("HeaderGeometry", "ACTION_CLUSTER_GAP"),
                    second.getMinX() - first.getMaxX(),
                    "HeaderGeometry.ACTION_CLUSTER_GAP");
        }
        buttons.stream().findFirst().ifPresent(button -> {
            Bounds buttonBounds = relativeBounds(sceneRoot, button, rootMinX, rootMinY);
            firstNode(button, ".astra-header-menu-graphic").ifPresent(graphic -> {
                Bounds graphicBounds = relativeBounds(sceneRoot, graphic, rootMinX, rootMinY);
                addMeasurement(measurements, "header button left content inset",
                        nestedStaticField("HeaderGeometry", "MENU_EDGE_MARGIN"),
                        graphicBounds.getMinX() - buttonBounds.getMinX(),
                        "HeaderGeometry.MENU_EDGE_MARGIN");
            });
        });
    }

    private static void addHeaderMenuMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> panel = firstNode(sceneRoot, ".astra-header-options-panel");
        Optional<Node> group = firstNode(sceneRoot, ".astra-header-options-group");
        if (panel.isPresent()) {
            Bounds panelBounds = relativeBounds(sceneRoot, panel.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "header menu panel width",
                    nestedStaticField("HeaderGeometry", "MENU_WIDTH"),
                    panelBounds.getWidth(),
                    "HeaderGeometry.MENU_WIDTH");
        }
        if (panel.isPresent() && group.isPresent()) {
            Bounds panelBounds = relativeBounds(sceneRoot, panel.get(), rootMinX, rootMinY);
            Bounds groupBounds = relativeBounds(sceneRoot, group.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "header menu panel left inset",
                    nestedStaticField("HeaderGeometry", "OPTIONS_PANEL_INSET")
                            + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    groupBounds.getMinX() - panelBounds.getMinX(),
                    "HeaderGeometry.OPTIONS_PANEL_INSET + SURFACE_BORDER_WIDTH");
            addMeasurement(measurements, "header menu panel right inset",
                    nestedStaticField("HeaderGeometry", "OPTIONS_PANEL_INSET")
                            + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    panelBounds.getMaxX() - groupBounds.getMaxX(),
                    "HeaderGeometry.OPTIONS_PANEL_INSET + SURFACE_BORDER_WIDTH");
        }
        List<Node> buttons = managedNodes(sceneRoot, ".astra-header-segment-button");
        if (buttons.size() >= 2) {
            Bounds first = relativeBounds(sceneRoot, buttons.get(0), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, buttons.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "header segment button gap",
                    nestedStaticField("HeaderGeometry", "SEGMENT_CONTROL_GAP"),
                    second.getMinX() - first.getMaxX(),
                    "HeaderGeometry.SEGMENT_CONTROL_GAP");
            addMeasurement(measurements, "header segment button width",
                    nestedStaticField("HeaderGeometry", "SEGMENT_BUTTON_WIDTH"),
                    first.getWidth(),
                    "HeaderGeometry.SEGMENT_BUTTON_WIDTH");
        }
    }

    private static void addClosedComboMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> combo = firstNode(sceneRoot, ".astra-combo");
        Optional<Node> cell = firstNode(sceneRoot, ".astra-combo-cell");
        Optional<Node> arrowButton = firstNode(sceneRoot, ".astra-combo-arrow-button");
        if (combo.isPresent() && cell.isPresent()) {
            Bounds comboBounds = relativeBounds(sceneRoot, combo.get(), rootMinX, rootMinY);
            Bounds cellBounds = relativeBounds(sceneRoot, cell.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "closed combo left cell inset",
                    nestedStaticField("ControlGeometry", "COMBO_CELL_HORIZONTAL_INSET")
                            + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    textOrNodeMinX(cell.get()) - rootMinX - comboBounds.getMinX(),
                    "ControlGeometry.COMBO_CELL_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
            arrowButton.ifPresent(arrow -> {
                Bounds arrowBounds = relativeBounds(sceneRoot, arrow, rootMinX, rootMinY);
                addMeasurement(measurements, "closed combo cell to arrow",
                        0.0,
                        arrowBounds.getMinX() - cellBounds.getMaxX(),
                        "combo cell and arrow button share edge");
                addMeasurement(measurements, "closed combo arrow to right edge",
                        0.0,
                        comboBounds.getMaxX() - arrowBounds.getMaxX(),
                        "arrow button reaches combo right edge");
            });
        }
    }

    private static void addComboPopupMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> list = firstNode(sceneRoot, ".list-view");
        List<Node> cells = managedNodes(sceneRoot, ".astra-combo-cell");
        if (list.isPresent() && !cells.isEmpty()) {
            Bounds listBounds = relativeBounds(sceneRoot, list.get(), rootMinX, rootMinY);
            Bounds cellBounds = relativeBounds(sceneRoot, cells.get(0), rootMinX, rootMinY);
            addMeasurement(measurements, "combo popup list left to row",
                    LauncherGeometryTokens.SURFACE_BORDER_WIDTH * 2.0,
                    cellBounds.getMinX() - listBounds.getMinX(),
                    "SURFACE_BORDER_WIDTH * 2");
            addMeasurement(measurements, "combo popup row text inset",
                    nestedStaticField("ControlGeometry", "COMBO_CELL_HORIZONTAL_INSET"),
                    textOrNodeMinX(cells.get(0)) - rootMinX - cellBounds.getMinX(),
                    "ControlGeometry.COMBO_CELL_HORIZONTAL_INSET");
            if (cells.size() >= 2) {
                Bounds second = relativeBounds(sceneRoot, cells.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "combo popup row gap",
                        0.0,
                        second.getMinY() - cellBounds.getMaxY(),
                        "combo popup rows are contiguous");
            }
        }
    }

    private static void addOutputMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> output = firstNode(sceneRoot, ".astra-output-pane");
        Optional<Node> statusCard = firstNode(sceneRoot, ".astra-log-status-card");
        Optional<Node> statusTitle = firstNode(sceneRoot, ".astra-log-status-title");
        Optional<Node> copy = firstNode(sceneRoot, ".astra-log-copy-button");
        Optional<Node> logScroll = firstNode(sceneRoot, ".astra-log-scroll");
        if (output.isPresent()) {
            Bounds outputBounds = relativeBounds(sceneRoot, output.get(), rootMinX, rootMinY);
            statusCard.ifPresent(card -> {
                Bounds cardBounds = relativeBounds(sceneRoot, card, rootMinX, rootMinY);
                addMeasurement(measurements, "output pane left inset to status card",
                        staticField("OUTPUT_PANE_INSET")
                                + styledLogField("LOG_ROW_GAP")
                                + (LauncherGeometryTokens.SURFACE_BORDER_WIDTH * 2.0),
                        cardBounds.getMinX() - outputBounds.getMinX(),
                        "OUTPUT_PANE_INSET + StyledLogView.LOG_ROW_GAP + (SURFACE_BORDER_WIDTH * 2)");
                addMeasurement(measurements, "output pane right inset to status card",
                        staticField("OUTPUT_PANE_INSET")
                                + styledLogField("LOG_ROW_GAP")
                                + (LauncherGeometryTokens.SURFACE_BORDER_WIDTH * 2.0),
                        outputBounds.getMaxX() - cardBounds.getMaxX(),
                        "OUTPUT_PANE_INSET + StyledLogView.LOG_ROW_GAP + (SURFACE_BORDER_WIDTH * 2)");
            });
            copy.ifPresent(button -> {
                Bounds copyBounds = relativeBounds(sceneRoot, button, rootMinX, rootMinY);
                addMeasurement(measurements, "copy button right rail",
                        staticField("OUTPUT_PANE_INSET")
                                + styledLogField("LOG_ROW_GAP")
                                + (LauncherGeometryTokens.SURFACE_BORDER_WIDTH * 2.0),
                        outputBounds.getMaxX() - copyBounds.getMaxX(),
                        "OUTPUT_PANE_INSET + StyledLogView.LOG_ROW_GAP + (SURFACE_BORDER_WIDTH * 2)");
            });
        }
        if (statusCard.isPresent() && statusTitle.isPresent()) {
            Bounds cardBounds = relativeBounds(sceneRoot, statusCard.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "log status card title left inset",
                    styledLogField("LOG_CARD_HORIZONTAL_INSET")
                            + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    textOrNodeMinX(statusTitle.get()) - rootMinX - cardBounds.getMinX(),
                    "StyledLogView.LOG_CARD_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
            addMeasurement(measurements, "log status card title top inset",
                    styledLogField("LOG_CARD_VERTICAL_INSET")
                            + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    relativeBounds(sceneRoot, statusTitle.get(), rootMinX, rootMinY).getMinY()
                            - cardBounds.getMinY(),
                    "StyledLogView.LOG_CARD_VERTICAL_INSET + SURFACE_BORDER_WIDTH");
        }
    }

    private static void addAllSettingsMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        addSectionMeasurements(sceneRoot, measurements, ".astra-section-content");
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        List<Node> sections = managedNodes(sceneRoot, ".astra-collapsible-section");
        if (sections.size() >= 2) {
            Bounds first = relativeBounds(sceneRoot, sections.get(0), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, sections.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "all settings section gap",
                    staticField("SECTION_CONTENT_GAP"),
                    second.getMinY() - first.getMaxY(),
                    "SECTION_CONTENT_GAP");
        }
    }

    private static void addAdvancedMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> panel = firstNode(sceneRoot, ".astra-advanced-settings-panel");
        firstNode(sceneRoot, ".astra-routine-settings-panel").ifPresent(settings -> panel.ifPresent(advanced -> {
            Bounds settingsBounds = relativeBounds(sceneRoot, settings, rootMinX, rootMinY);
            Bounds advancedBounds = relativeBounds(sceneRoot, advanced, rootMinX, rootMinY);
            addMeasurement(measurements, "routine to advanced panel gap",
                    LauncherGeometryTokens.OUTER_MARGIN,
                    advancedBounds.getMinY() - settingsBounds.getMaxY(),
                    "INPUT_STACK_GAP");
        }));
        addSectionMeasurements(sceneRoot, measurements, ".astra-section-content");
    }

    private static void addCustomControlMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        addSectionMeasurements(sceneRoot, measurements, ".astra-section-content-focused");
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> editor = firstNode(sceneRoot, ".astra-parameter-editor");
        if (editor.isPresent()) {
            List<Node> buttons = managedNodes(editor.get(), ".button");
            if (buttons.size() >= 2) {
                Bounds first = relativeBounds(sceneRoot, buttons.get(0), rootMinX, rootMinY);
                Bounds second = relativeBounds(sceneRoot, buttons.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "custom editor button gap",
                        nestedStaticField("SelectionGeometry", "DIALOG_ACTION_GAP"),
                        second.getMinX() - first.getMaxX(),
                        "SelectionGeometry.DIALOG_ACTION_GAP");
            }
        }
    }

    private static void addDialogMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> list = firstNode(sceneRoot, ".astra-list-view");
        firstNode(sceneRoot, ".astra-dialog-section-title").ifPresent(title ->
                list.ifPresent(listNode -> {
                    Bounds titleBounds = relativeBounds(sceneRoot, title, rootMinX, rootMinY);
                    Bounds listBounds = relativeBounds(sceneRoot, listNode, rootMinX, rootMinY);
                    addMeasurement(measurements, "dialog title to list gap",
                            nestedStaticField("SelectionGeometry", "LABEL_TO_LIST_GAP"),
                            listBounds.getMinY() - titleBounds.getMaxY(),
                            "SelectionGeometry.LABEL_TO_LIST_GAP");
                }));
    }

    private static void addSectionMeasurements(Node sceneRoot,
                                               List<GeometryMeasurement> measurements,
                                               String contentSelector) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> content = firstNode(sceneRoot, contentSelector);
        if (content.isEmpty()) {
            return;
        }
        Bounds contentBounds = relativeBounds(sceneRoot, content.get(), rootMinX, rootMinY);
        firstManagedNode(content.get(), ".astra-parameter-row").ifPresent(row -> {
            Bounds rowBounds = relativeBounds(sceneRoot, row, rootMinX, rootMinY);
            addMeasurement(measurements, "section content left padding",
                    LauncherGeometryTokens.INTRA_PANEL_MARGIN + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    rowBounds.getMinX() - contentBounds.getMinX(),
                    "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
        });
        firstManagedNode(content.get(), ".astra-parameter-editor").ifPresent(editor -> {
            Bounds editorBounds = relativeBounds(sceneRoot, editor, rootMinX, rootMinY);
            addMeasurement(measurements, "section content right padding",
                    LauncherGeometryTokens.INTRA_PANEL_MARGIN + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    contentBounds.getMaxX() - editorBounds.getMaxX(),
                    "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
        });
        consecutiveManagedRowsWithSameParent(content.get()).ifPresent(rows -> {
            Bounds first = relativeBounds(sceneRoot, rows.first(), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, rows.second(), rootMinX, rootMinY);
            addMeasurement(measurements, "section parameter row gap",
                    staticField("PARAMETER_ROW_GAP"),
                    second.getMinY() - first.getMaxY(),
                    "PARAMETER_ROW_GAP");
        });
        Map<String, Double> rails = railPositions(sceneRoot);
        addMeasurement(measurements, "section help column alignment",
                0.0,
                signedDistance(rails, "independent help", "dependent help"),
                "dependent and independent help rails match");
        addMeasurement(measurements, "section editor column alignment",
                0.0,
                signedDistance(rails, "independent editor", "dependent editor"),
                "dependent and independent editor rails match");
    }

    private static List<GeometryMeasurement> geometryMeasurements(Node sceneRoot) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Bounds root = relativeBounds(sceneRoot, sceneRoot, rootMinX, rootMinY);
        Optional<Node> headerNode = firstNode(sceneRoot, ".astra-animated-gradient-header");
        Optional<Node> inputNode = firstNode(sceneRoot, ".astra-settings-scroll");
        Optional<Node> outputNode = firstNode(sceneRoot, ".astra-output-pane");
        Optional<Node> runButton = buttonByText(sceneRoot, "Run");
        Optional<Node> channelPanel = firstNode(sceneRoot, ".astra-channel-panel");
        Optional<Node> settingsPanel = firstNode(sceneRoot, ".astra-routine-settings-panel");
        Optional<Node> advancedPanel = firstNode(sceneRoot, ".astra-advanced-unlock-panel");
        Optional<Node> focusedPanel = firstNode(sceneRoot, ".astra-routine-settings-panel");
        Optional<Node> parameterGrid = firstNode(sceneRoot, ".astra-section-content-focused");
        Optional<Node> focusedHeader = firstNode(sceneRoot, ".astra-focused-section-header");
        Optional<Node> dashboardFrame = firstNode(sceneRoot, ".astra-card-dashboard-frame");
        Optional<Node> dashboardGrid = firstNode(sceneRoot, ".astra-card-dashboard");
        Optional<Node> helpSummary = firstNode(sceneRoot, ".astra-help-summary-grid");
        Optional<Node> helpQuickTitle = firstNode(sceneRoot, ".astra-help-section-title");
        Optional<Node> helpBody = firstNode(sceneRoot, ".astra-help-body");
        Optional<Node> helpDetailsShell = firstNode(sceneRoot, ".astra-help-details-shell");
        Optional<Node> helpDetailsAccent = firstNode(sceneRoot, ".astra-help-details-accent");

        double outerMargin = LauncherGeometryTokens.OUTER_MARGIN;
        double intraPanelMargin = LauncherGeometryTokens.INTRA_PANEL_MARGIN;
        double borderWidth = LauncherGeometryTokens.SURFACE_BORDER_WIDTH;
        double scrollbarGutter = LauncherGeometryTokens.OUTER_MARGIN;
        double scrollbarThumb = scrollbarGutter / 3.0;
        double scrollbarSidePadding = (scrollbarGutter - scrollbarThumb) / 2.0;
        double interPaneGap = outerMargin - scrollbarSidePadding;
        double inputContentToBarGap = outerMargin - scrollbarSidePadding;
        double sectionContentGap = staticField("SECTION_CONTENT_GAP");

        List<GeometryMeasurement> measurements = new ArrayList<>();
        inputNode.ifPresent(input -> {
            Bounds inputBounds = relativeBounds(sceneRoot, input, rootMinX, rootMinY);
            addMeasurement(measurements, "left outer margin", outerMargin,
                    inputBounds.getMinX() - root.getMinX(), "OUTER_MARGIN");
            addMeasurement(measurements, "input pane to output pane", interPaneGap,
                    outputNode.map(output -> relativeBounds(sceneRoot, output, rootMinX, rootMinY).getMinX()
                            - inputBounds.getMaxX()).orElse(Double.NaN),
                    "OUTER_MARGIN - SCROLLBAR_SIDE_PADDING");
            verticalScrollBar(input).ifPresent(gutter -> {
                Bounds gutterBounds = relativeBounds(sceneRoot, gutter, rootMinX, rootMinY);
                addMeasurement(measurements, "scrollbar gutter width", scrollbarGutter,
                        gutterBounds.getWidth(), "SCROLLBAR_GUTTER_WIDTH");
                visibleScrollbarBar(gutter).ifPresent(bar -> {
                    Bounds barBounds = relativeBounds(sceneRoot, bar, rootMinX, rootMinY);
                    addMeasurement(measurements, "visible bar width", scrollbarThumb,
                            barBounds.getWidth(), "SCROLLBAR_GUTTER_WIDTH / 3");
                    inputContentRightEdge(input, sceneRoot, rootMinX, rootMinY).ifPresent(contentRight ->
                            addMeasurement(measurements, "input content to visible bar",
                                    inputContentToBarGap + scrollbarSidePadding,
                                    barBounds.getMinX() - contentRight,
                                    "INPUT_CONTENT_TO_BAR_GAP + SCROLLBAR_SIDE_PADDING"));
                    outputNode.ifPresent(output -> {
                        Bounds outputBounds = relativeBounds(sceneRoot, output, rootMinX, rootMinY);
                        addMeasurement(measurements, "visible bar to output pane",
                                scrollbarSidePadding + interPaneGap,
                                outputBounds.getMinX() - barBounds.getMaxX(),
                                "SCROLLBAR_SIDE_PADDING + INTER_PANE_GAP");
                    });
                });
            });
        });
        outputNode.ifPresent(output -> {
            Bounds outputBounds = relativeBounds(sceneRoot, output, rootMinX, rootMinY);
            addMeasurement(measurements, "right outer margin", outerMargin,
                    root.getMaxX() - outputBounds.getMaxX(), "OUTER_MARGIN");
            inputNode.ifPresent(input -> {
                Bounds inputBounds = relativeBounds(sceneRoot, input, rootMinX, rootMinY);
                addMeasurement(measurements, "input pane bottom to output pane bottom",
                        0.0,
                        outputBounds.getMaxY() - inputBounds.getMaxY(),
                        "input/output panes share workspace bottom");
            });
        });
        if (headerNode.isPresent() && inputNode.isPresent()) {
            Bounds header = relativeBounds(sceneRoot, headerNode.get(), rootMinX, rootMinY);
            Bounds input = relativeBounds(sceneRoot, inputNode.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "header to input pane", outerMargin,
                    input.getMinY() - header.getMaxY(), "workspace top OUTER_MARGIN");
        }
        if (outputNode.isPresent() && runButton.isPresent()) {
            Bounds output = relativeBounds(sceneRoot, outputNode.get(), rootMinX, rootMinY);
            Bounds run = relativeBounds(sceneRoot, runButton.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "output pane to run button", outerMargin,
                    run.getMinY() - output.getMaxY(), "main action bar top OUTER_MARGIN");
            addMeasurement(measurements, "run button to bottom", outerMargin,
                    root.getMaxY() - run.getMaxY(), "main action bar bottom OUTER_MARGIN");
            addMeasurement(measurements, "run button to right edge", outerMargin,
                    root.getMaxX() - run.getMaxX(), "main action bar right OUTER_MARGIN");
        }
        if (channelPanel.isPresent() && settingsPanel.isPresent()) {
            Bounds channel = relativeBounds(sceneRoot, channelPanel.get(), rootMinX, rootMinY);
            Bounds settings = relativeBounds(sceneRoot, settingsPanel.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "channel panel to settings panel", outerMargin,
                    settings.getMinY() - channel.getMaxY(), "INPUT_STACK_GAP");
        }
        if (settingsPanel.isPresent() && advancedPanel.isPresent()) {
            Bounds settings = relativeBounds(sceneRoot, settingsPanel.get(), rootMinX, rootMinY);
            Bounds advanced = relativeBounds(sceneRoot, advancedPanel.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "settings panel to advanced panel", outerMargin,
                    advanced.getMinY() - settings.getMaxY(), "INPUT_STACK_GAP");
        }
        if (focusedPanel.isPresent() && parameterGrid.isPresent()) {
            Bounds panel = relativeBounds(sceneRoot, focusedPanel.get(), rootMinX, rootMinY);
            Bounds grid = relativeBounds(sceneRoot, parameterGrid.get(), rootMinX, rootMinY);
            double borderedInset = intraPanelMargin + borderWidth;
            addMeasurement(measurements, "focused panel left inset", borderedInset,
                    grid.getMinX() - panel.getMinX(), "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
            addMeasurement(measurements, "focused panel right inset", borderedInset,
                    panel.getMaxX() - grid.getMaxX(), "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
            focusedHeader.ifPresent(header -> {
                Bounds headerBounds = relativeBounds(sceneRoot, header, rootMinX, rootMinY);
                addMeasurement(measurements, "focused header to parameter grid",
                        sectionContentGap,
                        grid.getMinY() - headerBounds.getMaxY(),
                        "SECTION_CONTENT_GAP");
            });
            firstManagedNode(parameterGrid.get(), ".astra-parameter-row").ifPresent(row -> {
                Bounds rowBounds = relativeBounds(sceneRoot, row, rootMinX, rootMinY);
                addMeasurement(measurements, "parameter grid left padding",
                        intraPanelMargin + borderWidth,
                        rowBounds.getMinX() - grid.getMinX(),
                        "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
            });
            firstManagedNode(parameterGrid.get(), ".astra-parameter-editor").ifPresent(editor -> {
                Bounds editorBounds = relativeBounds(sceneRoot, editor, rootMinX, rootMinY);
                addMeasurement(measurements, "editor to parameter grid right",
                        intraPanelMargin + borderWidth,
                        grid.getMaxX() - editorBounds.getMaxX(),
                        "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
            });
            consecutiveManagedRowsWithSameParent(parameterGrid.get()).ifPresent(rows -> {
                Bounds first = relativeBounds(sceneRoot, rows.first(), rootMinX, rootMinY);
                Bounds second = relativeBounds(sceneRoot, rows.second(), rootMinX, rootMinY);
                addMeasurement(measurements, "parameter row to row gap",
                        staticField("PARAMETER_ROW_GAP"),
                        second.getMinY() - first.getMaxY(),
                        "PARAMETER_ROW_GAP");
            });
        }
        Map<String, Double> rails = railPositions(sceneRoot);
        addMeasurement(measurements, "independent box edge to bar",
                staticField("ACCENT_INDENT"),
                distance(rails, "independent box left", "independent bar left"),
                "ACCENT_INDENT");
        addMeasurement(measurements, "dependent panel edge to bar",
                staticField("ACCENT_INDENT"),
                distance(rails, "dependent panel left", "bar left"),
                "ACCENT_INDENT");
        addMeasurement(measurements, "independent edge to bar",
                staticField("PARAMETER_ROW_EDGE_TO_BAR_GAP"),
                distance(rails, "independent row left", "independent bar left"),
                "PARAMETER_ROW_EDGE_TO_BAR_GAP");
        addMeasurement(measurements, "independent bar to text",
                staticField("PARAMETER_BAR_TO_TEXT_GAP"),
                distance(rails, "independent bar right", "independent label"),
                "PARAMETER_BAR_TO_TEXT_GAP");
        addMeasurement(measurements, "dependent bar to text",
                staticField("PARAMETER_BAR_TO_TEXT_GAP"),
                distance(rails, "bar right", "dependent row label"),
                "PARAMETER_BAR_TO_TEXT_GAP");
        addMeasurement(measurements, "dependent title to dependent row label",
                0.0,
                signedDistance(rails, "dependent title", "dependent row label"),
                "dependent title uses dependent text rail");
        addMeasurement(measurements, "independent help to dependent help",
                0.0,
                signedDistance(rails, "independent help", "dependent help"),
                "same help column rail");
        addMeasurement(measurements, "independent editor to dependent editor",
                0.0,
                signedDistance(rails, "independent editor", "dependent editor"),
                "same editor rail");
        addMeasurement(measurements, "independent help to editor",
                staticField("EDITOR_RAIL") - staticField("HELP_RAIL") - staticField("PARAMETER_HELP_BUTTON_SIZE"),
                distance(rails, "independent help right", "independent editor"),
                "EDITOR_RAIL - HELP_RAIL - PARAMETER_HELP_BUTTON_SIZE");
        addMeasurement(measurements, "dependent help to editor",
                staticField("EDITOR_RAIL") - staticField("HELP_RAIL") - staticField("PARAMETER_HELP_BUTTON_SIZE"),
                distance(rails, "dependent help right", "dependent editor"),
                "EDITOR_RAIL - HELP_RAIL - PARAMETER_HELP_BUTTON_SIZE");
        if (parameterGrid.isPresent()) {
            Bounds grid = relativeBounds(sceneRoot, parameterGrid.get(), rootMinX, rootMinY);
            firstManagedNode(parameterGrid.get(), ".astra-dependent-panel").ifPresent(panel -> {
                Bounds panelBounds = relativeBounds(sceneRoot, panel, rootMinX, rootMinY);
                addMeasurement(measurements, "dependent panel outer left margin",
                        intraPanelMargin + borderWidth + staticField("DEPENDENT_PANEL_OUTER_LEFT_MARGIN"),
                        panelBounds.getMinX() - grid.getMinX(),
                        "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH + DEPENDENT_PANEL_OUTER_LEFT_MARGIN");
                addMeasurement(measurements, "dependent panel fills grid right edge",
                        intraPanelMargin + borderWidth,
                        grid.getMaxX() - panelBounds.getMaxX(),
                        "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
                firstManagedNode(panel, ".astra-dependent-panel-rows").ifPresent(rows -> {
                    Bounds rowsBounds = relativeBounds(sceneRoot, rows, rootMinX, rootMinY);
                    addMeasurement(measurements, "dependent rows container left border inset",
                            borderWidth,
                            rowsBounds.getMinX() - panelBounds.getMinX(),
                            "SURFACE_BORDER_WIDTH");
                    addMeasurement(measurements, "dependent rows container right inset",
                            staticField("DEPENDENT_PANEL_RIGHT_PADDING") + borderWidth,
                            panelBounds.getMaxX() - rowsBounds.getMaxX(),
                            "DEPENDENT_PANEL_RIGHT_PADDING + SURFACE_BORDER_WIDTH");
                });
                firstManagedNode(panel, ".astra-dependent-panel-title").ifPresent(title -> {
                    Bounds titleBounds = relativeBounds(sceneRoot, title, rootMinX, rootMinY);
                    addMeasurement(measurements, "dependent title text inset",
                            staticField("DEPENDENT_TITLE_TEXT_INSET") + borderWidth,
                            textOrNodeMinX(title) - rootMinX - panelBounds.getMinX(),
                            "DEPENDENT_TITLE_TEXT_INSET + SURFACE_BORDER_WIDTH");
                    firstManagedNode(panel, ".astra-dependent-panel-reason").ifPresent(reason -> {
                        Bounds reasonBounds = relativeBounds(sceneRoot, reason, rootMinX, rootMinY);
                        addMeasurement(measurements, "dependent title to reason gap",
                                LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                                reasonBounds.getMinY() - titleBounds.getMaxY(),
                                "INTRA_PANEL_TIGHT_GAP");
                        firstManagedNode(panel, ".astra-dependent-panel-rows").ifPresent(rows -> {
                            Bounds rowsBounds = relativeBounds(sceneRoot, rows, rootMinX, rootMinY);
                            addMeasurement(measurements, "dependent reason to row grid gap",
                                    LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                                    rowsBounds.getMinY() - reasonBounds.getMaxY(),
                                    "INTRA_PANEL_TIGHT_GAP");
                        });
                    });
                });
            });
        }
        if (dashboardFrame.isPresent() && dashboardGrid.isPresent()) {
            Bounds frame = relativeBounds(sceneRoot, dashboardFrame.get(), rootMinX, rootMinY);
            Bounds grid = relativeBounds(sceneRoot, dashboardGrid.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "dashboard grid left frame inset",
                    0.0,
                    grid.getMinX() - frame.getMinX(),
                    "dashboard frame has FLUSH padding");
            addMeasurement(measurements, "dashboard grid right frame inset",
                    0.0,
                    frame.getMaxX() - grid.getMaxX(),
                    "dashboard frame has FLUSH padding");
            List<Node> cards = managedNodes(dashboardGrid.get(), ".astra-settings-card");
            if (cards.size() >= 2) {
                Bounds first = relativeBounds(sceneRoot, cards.get(0), rootMinX, rootMinY);
                Bounds second = relativeBounds(sceneRoot, cards.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "dashboard card column gap",
                        sectionContentGap,
                        second.getMinX() - first.getMaxX(),
                        "SECTION_CONTENT_GAP");
            }
            if (cards.size() >= 4) {
                Bounds first = relativeBounds(sceneRoot, cards.get(0), rootMinX, rootMinY);
                Bounds fourth = relativeBounds(sceneRoot, cards.get(3), rootMinX, rootMinY);
                addMeasurement(measurements, "dashboard card row gap",
                        sectionContentGap,
                        fourth.getMinY() - first.getMaxY(),
                        "SECTION_CONTENT_GAP");
            }
            cards.stream().findFirst()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getGraphic() != null)
                    .ifPresent(card -> {
                        Bounds cardBounds = relativeBounds(sceneRoot, card, rootMinX, rootMinY);
                        Bounds graphicBounds = relativeBounds(sceneRoot, card.getGraphic(), rootMinX, rootMinY);
                        addMeasurement(measurements, "dashboard card left internal padding",
                                staticField("DASHBOARD_CARD_INSET") + borderWidth,
                                graphicBounds.getMinX() - cardBounds.getMinX(),
                                "DASHBOARD_CARD_INSET + SURFACE_BORDER_WIDTH");
                    });
        }
        if (helpSummary.isPresent() && helpQuickTitle.isPresent()) {
            Bounds summary = relativeBounds(sceneRoot, helpSummary.get(), rootMinX, rootMinY);
            Bounds quickTitle = relativeBounds(sceneRoot, helpQuickTitle.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "help summary to next section title",
                    staticField("HELP_DIALOG_SECTION_GAP"),
                    quickTitle.getMinY() - summary.getMaxY(),
                    "HELP_DIALOG_SECTION_GAP");
        }
        if (helpQuickTitle.isPresent() && helpBody.isPresent()) {
            Bounds quickTitle = relativeBounds(sceneRoot, helpQuickTitle.get(), rootMinX, rootMinY);
            Bounds body = relativeBounds(sceneRoot, helpBody.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "help section title to body",
                    staticField("HELP_DIALOG_SECTION_GAP"),
                    body.getMinY() - quickTitle.getMaxY(),
                    "HELP_DIALOG_SECTION_GAP");
        }
        if (helpDetailsAccent.isPresent()) {
            Bounds accent = relativeBounds(sceneRoot, helpDetailsAccent.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "help details accent width",
                    staticField("HELP_DETAIL_ACCENT_WIDTH"),
                    accent.getWidth(),
                    "HELP_DETAIL_ACCENT_WIDTH");
        }
        if (helpDetailsShell.isPresent()) {
            firstManagedNode(helpDetailsShell.get(), ".astra-help-details-cards").ifPresent(cards -> {
                Bounds shell = relativeBounds(sceneRoot, helpDetailsShell.get(), rootMinX, rootMinY);
                Bounds cardBounds = relativeBounds(sceneRoot, cards, rootMinX, rootMinY);
                addMeasurement(measurements, "help details cards right inset",
                        staticField("HELP_DIALOG_INSET") - (borderWidth * 2.0),
                        shell.getMaxX() - cardBounds.getMaxX(),
                        "HELP_DIALOG_INSET - (SURFACE_BORDER_WIDTH * 2)");
            });
            List<Node> cards = managedNodes(helpDetailsShell.get(), ".astra-help-detail-card");
            if (cards.size() >= 2) {
                Bounds first = relativeBounds(sceneRoot, cards.get(0), rootMinX, rootMinY);
                Bounds second = relativeBounds(sceneRoot, cards.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "help detail card vertical gap",
                        staticField("HELP_DETAIL_CARD_GAP"),
                        second.getMinY() - first.getMaxY(),
                        "HELP_DETAIL_CARD_GAP");
            }
        }
        return measurements;
    }

    private static void addMeasurement(List<GeometryMeasurement> measurements,
                                       String label,
                                       double expected,
                                       double observed,
                                       String formula) {
        if (!Double.isNaN(observed)) {
            measurements.add(new GeometryMeasurement(label, expected, observed, formula));
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
                    node.getParent().lookupAll(".astra-button-help").stream()
                            .findFirst()
                            .ifPresent(help -> {
                                Bounds helpBounds = help.localToScene(help.getBoundsInLocal());
                                markers.add(new RailMarker(
                                        "independent help",
                                        helpBounds.getMinX() - rootMinX,
                                        new Color(255, 102, 0)));
                                markers.add(new RailMarker(
                                        "independent help right",
                                        helpBounds.getMaxX() - rootMinX,
                                        new Color(255, 190, 11)));
                            });
                    firstNode(root, ".astra-section-content-focused")
                            .flatMap(content -> nthNode(content, ".astra-parameter-editor", 0))
                            .ifPresent(editor -> markers.add(new RailMarker(
                                    "independent editor",
                                    editor.localToScene(editor.getBoundsInLocal()).getMinX() - rootMinX,
                                    new Color(46, 196, 182))));
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
                            .ifPresent(help -> {
                                Bounds helpBounds = help.localToScene(help.getBoundsInLocal());
                                markers.add(new RailMarker(
                                        "dependent help",
                                        helpBounds.getMinX() - rootMinX,
                                        new Color(255, 0, 110)));
                                markers.add(new RailMarker(
                                        "dependent help right",
                                        helpBounds.getMaxX() - rootMinX,
                                        new Color(255, 115, 166)));
                            });
                    panel.lookupAll(".astra-parameter-editor").stream()
                            .findFirst()
                            .ifPresent(editor -> markers.add(new RailMarker(
                                    "dependent editor",
                                    editor.localToScene(editor.getBoundsInLocal()).getMinX() - rootMinX,
                                    new Color(87, 117, 144))));
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

    private static void drawBoundsMarkers(BufferedImage image, List<BoundsMarker> markers) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            for (BoundsMarker marker : markers) {
                Bounds b = marker.bounds();
                g.setColor(marker.color());
                g.setStroke(new BasicStroke(2.0f));
                g.drawRoundRect(
                        (int)Math.round(b.getMinX()),
                        (int)Math.round(b.getMinY()),
                        (int)Math.round(b.getWidth()),
                        (int)Math.round(b.getHeight()),
                        10,
                        10);
                drawLabel(g,
                        marker.label(),
                        (int)Math.round(b.getMinX()) + 6,
                        (int)Math.round(b.getMinY()) + 16,
                        marker.color());
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

    private static void writeGeometryTables(String name,
                                            List<GeometryMeasurement> measurements) throws IOException {
        Path csv = options.outputPath().resolve(name + ".csv");
        Path markdown = options.outputPath().resolve(name + ".md");
        StringBuilder csvText = new StringBuilder("label,expected_px,observed_px,delta_px,formula\n");
        StringBuilder mdText = new StringBuilder();
        mdText.append("| Measurement | Expected px | Observed px | Delta px | Formula |\n");
        mdText.append("| --- | ---: | ---: | ---: | --- |\n");
        for (GeometryMeasurement measurement : measurements) {
            csvText.append(csvEscape(measurement.label())).append(',')
                    .append(format(measurement.expected())).append(',')
                    .append(format(measurement.observed())).append(',')
                    .append(format(measurement.delta())).append(',')
                    .append(csvEscape(measurement.formula())).append('\n');
            mdText.append("| ")
                    .append(measurement.label()).append(" | ")
                    .append(format(measurement.expected())).append(" | ")
                    .append(format(measurement.observed())).append(" | ")
                    .append(format(measurement.delta())).append(" | `")
                    .append(measurement.formula()).append("` |\n");
        }
        Files.writeString(csv, csvText.toString());
        Files.writeString(markdown, mdText.toString());
        System.out.println(csv.toAbsolutePath());
        System.out.println(markdown.toAbsolutePath());
    }

    private static String csvEscape(String text) {
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static Map<String, Double> railPositions(Node root) {
        Map<String, Double> rails = new HashMap<>();
        railMarkers(root).forEach(marker -> rails.put(marker.label(), marker.x()));
        return rails;
    }

    private static double distance(Map<String, Double> rails, String start, String end) {
        if (!rails.containsKey(start) || !rails.containsKey(end)) {
            return Double.NaN;
        }
        return Math.abs(rails.get(end) - rails.get(start));
    }

    private static double signedDistance(Map<String, Double> rails, String start, String end) {
        if (!rails.containsKey(start) || !rails.containsKey(end)) {
            return Double.NaN;
        }
        return rails.get(end) - rails.get(start);
    }

    private static double staticField(String name) {
        try {
            return staticField(PipelineLauncher.class, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read PipelineLauncher geometry field " + name, e);
        }
    }

    private static double nestedStaticField(String simpleClassName, String name) {
        for (Class<?> declared : PipelineLauncher.class.getDeclaredClasses()) {
            if (declared.getSimpleName().equals(simpleClassName)) {
                try {
                    return staticField(declared, name);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Unable to read PipelineLauncher."
                            + simpleClassName + " geometry field " + name, e);
                }
            }
        }
        throw new IllegalStateException("Unable to find PipelineLauncher nested class " + simpleClassName);
    }

    private static double styledLogField(String name) {
        try {
            return staticField(StyledLogView.class, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read StyledLogView geometry field " + name, e);
        }
    }

    private static double staticField(Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(null);
    }

    private static Optional<Node> firstNode(Node root, String styleClass) {
        return root.lookupAll(styleClass).stream().findFirst();
    }

    private static Optional<Node> nthNode(Node root, String styleClass, int index) {
        List<Node> nodes = root.lookupAll(styleClass).stream().toList();
        return index < nodes.size() ? Optional.of(nodes.get(index)) : Optional.empty();
    }

    private static Optional<Node> firstManagedNode(Node root, String styleClass) {
        return managedNodes(root, styleClass).stream().findFirst();
    }

    private static List<Node> managedNodes(Node root, String styleClass) {
        return root.lookupAll(styleClass).stream()
                .filter(Node::isManaged)
                .toList();
    }

    private static Optional<RowPair> consecutiveManagedRowsWithSameParent(Node root) {
        List<Node> rows = managedNodes(root, ".astra-parameter-row");
        for (int i = 0; i + 1 < rows.size(); i++) {
            Node first = rows.get(i);
            Node second = rows.get(i + 1);
            if (first.getParent() == second.getParent()) {
                return Optional.of(new RowPair(first, second));
            }
        }
        return Optional.empty();
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
                .max(java.util.Comparator.comparingDouble(node ->
                        node.localToScene(node.getBoundsInLocal()).getMaxX()));
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

    private record RowPair(Node first, Node second) {
    }

    private record BoundsMarker(String label, Bounds bounds, Color color) {
    }

    private record DistanceMarker(String label, boolean horizontal,
                                  double start, double end, double cross) {
        private double distance() {
            return Math.abs(end - start);
        }
    }

    private record GeometryMeasurement(String label, double expected,
                                       double observed, String formula) {
        private double delta() {
            return observed - expected;
        }
    }

    private enum Surface {
        HEADER,
        HEADER_MENU,
        COMBO_CLOSED,
        COMBO_POPUP,
        OUTPUT,
        ALL_SETTINGS,
        ADVANCED,
        CUSTOM_CONTROLS,
        DIALOG
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
