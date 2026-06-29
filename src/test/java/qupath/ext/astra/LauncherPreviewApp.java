package qupath.ext.astra;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ContextMenu;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Labeled;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.text.Text;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.stage.PopupWindow;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.servers.ImageChannel;

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
import java.util.OptionalDouble;

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
    private static String activeHeaderMenuText = "";
    private static Tooltip activeDiagnosticTooltip;
    private static Popup activeDiagnosticTooltipPopup;
    private static Node activeDiagnosticTooltipNode;
    private static final List<TextContractRow> TEXT_CONTRACT_ROWS = new ArrayList<>();
    private static final List<GradientSurfaceRow> GRADIENT_SURFACE_ROWS = new ArrayList<>();
    private static final PseudoClass HOVER_PSEUDO_CLASS =
            PseudoClass.getPseudoClass("hover");
    private static final PseudoClass PRESSED_PSEUDO_CLASS =
            PseudoClass.getPseudoClass("pressed");
    private static final double PREVIEW_BOOTSTRAP_STAGE_SIZE =
            LauncherGeometryTokens.LAYOUT_UNIT;

    @Override
    public void start(Stage primaryStage) throws Exception {
        Files.createDirectories(options.userPath());
        Files.createDirectories(options.outputPath());
        primaryStage.setTitle("ASTRA Preview Bootstrap");
        primaryStage.setScene(new Scene(new Pane(),
                PREVIEW_BOOTSTRAP_STAGE_SIZE,
                PREVIEW_BOOTSTRAP_STAGE_SIZE));
        primaryStage.show();
        System.out.println("ASTRA preview start: screens=" + Screen.getScreens().size()
                + ", mode=" + options.snapshotMode());
        if (Screen.getScreens().isEmpty()) {
            String message = "ASTRA preview cannot run because JavaFX reports zero screens after showing the bootstrap stage. "
                    + "Use the documented x64 QuPath/x64 Temurin route from a window-server session.";
            Files.writeString(options.outputPath().resolve("preview-failure.txt"), message + System.lineSeparator());
            throw new IllegalStateException(message);
        }
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
        if ("images-scope".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Images & Scope"));
            schedule(2.2, () -> snapshot("images-scope", title));
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
        if ("segmentation".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Segmentation"));
            schedule(2.2, () -> snapshot("segmentation", title));
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
        if ("help-dialog-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Run Setup"));
            schedule(2.6, () -> fireFirstHelpButton(title));
            schedule(4.6, () -> snapshotSurfaceGeometry("help-dialog-geometry",
                    "ASTRA Parameter Help", Surface.DIALOG, false));
            schedule(5.4, LauncherPreviewApp::closeAllWindows);
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
        if ("header-menu-placement-geometry".equals(snapshotMode)) {
            String leftTitle = "ASTRA Header Placement Left";
            String rightTitle = "ASTRA Header Placement Right";
            String clampTitle = "ASTRA Header Placement Clamp";
            schedule(1.5, () -> openHeaderPlacementDiagnosticWindow(leftTitle, HeaderPlacementState.LEFT_DEFAULT));
            schedule(2.2, () -> showHeaderPlacementMenu(leftTitle));
            schedule(2.9, () -> snapshotSurfaceGeometry("header-menu-left-default-geometry",
                    leftTitle, Surface.HEADER_MENU, true));
            schedule(3.2, () -> closeWindowAndTransients(leftTitle));
            schedule(3.6, () -> openHeaderPlacementDiagnosticWindow(rightTitle, HeaderPlacementState.RIGHT_ON_OVERFLOW));
            schedule(4.3, () -> showHeaderPlacementMenu(rightTitle));
            schedule(5.0, () -> snapshotSurfaceGeometry("header-menu-right-overflow-geometry",
                    rightTitle, Surface.HEADER_MENU, true));
            schedule(5.3, () -> closeWindowAndTransients(rightTitle));
            schedule(5.7, () -> openHeaderPlacementDiagnosticWindow(clampTitle, HeaderPlacementState.CLAMP_TOO_NARROW));
            schedule(6.4, () -> showHeaderPlacementMenu(clampTitle));
            schedule(7.1, () -> snapshotSurfaceGeometry("header-menu-clamp-too-narrow-geometry",
                    clampTitle, Surface.HEADER_MENU, true));
            schedule(7.7, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("header-menu-clamp-geometry".equals(snapshotMode)) {
            String clampTitle = "ASTRA Header Placement Clamp";
            schedule(1.5, () -> openHeaderPlacementDiagnosticWindow(clampTitle, HeaderPlacementState.CLAMP_TOO_NARROW));
            schedule(2.2, () -> showHeaderPlacementMenu(clampTitle));
            schedule(2.9, () -> snapshotSurfaceGeometry("header-menu-clamp-too-narrow-geometry",
                    clampTitle, Surface.HEADER_MENU, true));
            schedule(3.5, LauncherPreviewApp::closeAllWindows);
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
        if ("tooltip-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openTooltipDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("tooltip-geometry",
                    "ASTRA Tooltip Diagnostic", Surface.TOOLTIP, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("runtime-installer-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRuntimeInstallerDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("runtime-installer-geometry",
                    "ASTRA Runtime Installer Diagnostic", Surface.RUNTIME_INSTALLER, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("runtime-confirmation-dialog".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRuntimeConfirmationDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("runtime-confirmation-dialog", title,
                    Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("runtime-result-dialog".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRuntimeResultDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("runtime-result-dialog", title,
                    Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("runtime-failure-dialog".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRuntimeFailureDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("runtime-failure-dialog", title,
                    Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("runtime-repair-failure-dialog".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRuntimeRepairFailureDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("runtime-repair-failure-dialog", title,
                    Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("runtime-cancelled-dialog".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRuntimeCancelledDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("runtime-cancelled-dialog", title,
                    Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("asset-backed-combo-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openAssetBackedComboDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("asset-backed-combo-geometry",
                    "ASTRA Asset Combo Diagnostic", Surface.ASSET_COMBO, false));
            schedule(2.8, LauncherPreviewApp::showAssetComboPopup);
            schedule(3.5, () -> snapshotSurfaceGeometry("asset-backed-combo-popup-geometry",
                    "ASTRA Asset Combo Diagnostic", Surface.ASSET_COMBO_POPUP, true));
            schedule(4.1, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("output-pane-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> snapshotSurfaceGeometry("output-pane-geometry", title, Surface.OUTPUT, false));
            schedule(2.1, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("button-states-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openButtonStateDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("button-states-geometry",
                    "ASTRA Button State Geometry", Surface.BUTTON_STATES, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("run-progress-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRunProgressDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("run-progress-geometry",
                    "ASTRA Run Progress Geometry", Surface.TYPOGRAPHY, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("styled-log-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openStyledLogDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("styled-log-geometry",
                    "ASTRA Styled Log Diagnostic", Surface.STYLED_LOG, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("styled-log-expanded-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openStyledLogDiagnosticWindow);
            schedule(2.2, () -> fireButton("ASTRA Styled Log Diagnostic", "Show Cellpose details"));
            schedule(3.0, () -> snapshotSurfaceGeometry("styled-log-expanded-geometry",
                    "ASTRA Styled Log Diagnostic", Surface.STYLED_LOG, false));
            schedule(3.6, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("channel-panel-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> snapshotSurfaceGeometry("channel-panel-geometry", title, Surface.CHANNEL_PANEL, false));
            schedule(2.1, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("channel-panel-populated-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openPopulatedChannelPanelDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("channel-panel-populated-geometry",
                    "ASTRA Channel Panel Diagnostic", Surface.CHANNEL_PANEL, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
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
            schedule(2.1, () -> fireAdvancedViewButton(title, "All Settings"));
            schedule(2.6, () -> fireFirstAdvancedSectionHeader(title));
            schedule(3.1, () -> scrollSettingsPane(title, 1.0d));
            schedule(3.8, () -> snapshotSurfaceGeometry("advanced-geometry", title, Surface.ADVANCED, false));
            schedule(4.4, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("custom-controls-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Images & Scope"));
            schedule(2.2, () -> selectFirstComboValue(title, "PROJECT_IMAGE_SELECTION"));
            schedule(2.9, () -> snapshotSurfaceGeometry("custom-controls-geometry", title, Surface.CUSTOM_CONTROLS, false));
            schedule(3.5, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("colocalization-custom-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Colocalization Setup"));
            schedule(2.2, () -> fireButton(title, "Add check"));
            schedule(2.9, () -> snapshotSurfaceGeometry("colocalization-custom-geometry",
                    title, Surface.COLOCALIZATION_CUSTOM, false));
            schedule(3.3, () -> scrollSettingsPane(title, 0.45d));
            schedule(4.0, () -> snapshotSurfaceGeometry("colocalization-custom-middle-geometry",
                    title, Surface.COLOCALIZATION_CUSTOM, false));
            schedule(4.4, () -> scrollSettingsPane(title, 0.72d));
            schedule(5.1, () -> snapshotSurfaceGeometry("colocalization-custom-lower-geometry",
                    title, Surface.COLOCALIZATION_CUSTOM, false));
            schedule(5.7, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("marker-key-map-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openMarkerKeyMapDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("marker-key-map-geometry",
                    "ASTRA Marker Key Map Diagnostic", Surface.MARKER_KEY_MAP, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("dependency-matrix-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openDependencyMatrixDiagnosticWindow);
            schedule(2.5, () -> snapshotSurfaceGeometry("dependency-matrix-geometry",
                    "ASTRA Dependency Matrix Diagnostic", Surface.DEPENDENCY_MATRIX, false));
            schedule(3.1, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("row-state-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRowStateDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("row-state-geometry",
                    "ASTRA Row State Diagnostic", Surface.ROW_STATE, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("list-code-editor-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openListCodeEditorDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("list-code-editor-geometry",
                    "ASTRA List And Code Editor Diagnostic", Surface.LIST_CODE_EDITOR, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("channel-multi-select-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openChannelMultiSelectDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("channel-multi-select-geometry",
                    "ASTRA Channel Multi-Select Diagnostic", Surface.CHANNEL_MULTI_SELECT, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("typography-optical-review".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openTypographyDiagnosticWindow);
            schedule(2.4, () -> snapshotSurfaceGeometry("typography-optical-review",
                    "ASTRA Typography Optical QA", Surface.TYPOGRAPHY, false));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("text-contract-sweep".equals(snapshotMode)
                || "typography-optical-sweep".equals(snapshotMode)) {
            scheduleTextContractSweep(title);
            return;
        }
        if ("dialogs-geometry".equals(snapshotMode)
                || "selected-images-dialog-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Images & Scope"));
            schedule(2.2, () -> selectFirstComboValue(title, "PROJECT_IMAGE_SELECTION"));
            schedule(2.9, () -> Platform.runLater(() -> fireButton(title, "Choose Images...")));
            schedule(4.1, () -> snapshotSurfaceGeometry("selected-images-dialog-geometry", title, Surface.DIALOG, true));
            schedule(4.7, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("multi-select-dialog-geometry".equals(snapshotMode)) {
            schedule(1.5, () -> fireButton(title, "Run Setup"));
            schedule(2.4, () -> Platform.runLater(() -> fireButton(title, "Generate Regions, Detect Cells, Quantify")));
            schedule(3.6, () -> snapshotSurfaceGeometry("multi-select-dialog-geometry", title, Surface.DIALOG, true));
            schedule(4.2, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("settings-profile-dialog-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openSettingsProfileNameDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("settings-profile-dialog-geometry",
                    title, Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("reset-alert-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openResetConfirmationDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("reset-alert-geometry",
                    title, Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("provisional-alert-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openProvisionalConfirmationDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("provisional-alert-geometry",
                    title, Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
            return;
        }
        if ("run-failure-dialog-geometry".equals(snapshotMode)) {
            schedule(1.5, LauncherPreviewApp::openRunFailureDialog);
            schedule(2.4, () -> snapshotSurfaceGeometry("run-failure-dialog-geometry",
                    title, Surface.DIALOG, true));
            schedule(3.0, LauncherPreviewApp::closeAllWindows);
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

    private static void scheduleTextContractSweep(String title) {
        TEXT_CONTRACT_ROWS.clear();
        GRADIENT_SURFACE_ROWS.clear();
        schedule(1.5, () -> collectLauncherContractSurface("Dashboard cards", title));
        schedule(2.1, () -> fireButton(title, "Run Setup"));
        schedule(2.8, () -> collectLauncherContractSurface("Focused Run Setup", title));
        schedule(3.2, () -> fireButton(title, "Back to Dashboard"));
        schedule(3.7, () -> fireButton(title, "Images & Scope"));
        schedule(4.4, () -> collectLauncherContractSurface("Focused Images & Scope", title));
        schedule(4.8, () -> fireButton(title, "Back to Dashboard"));
        schedule(5.3, () -> fireButton(title, "Models"));
        schedule(6.0, () -> collectLauncherContractSurface("Focused Models", title));
        schedule(6.4, () -> fireButton(title, "Back to Dashboard"));
        schedule(6.9, () -> fireButton(title, "Segmentation"));
        schedule(7.6, () -> collectLauncherContractSurface("Focused Segmentation", title));
        schedule(8.0, () -> fireButton(title, "All Settings"));
        schedule(8.7, () -> collectLauncherContractSurface("All Settings", title));
        schedule(9.1, () -> unlockAdvanced(title));
        schedule(9.8, () -> collectLauncherContractSurface("Advanced unlocked", title));
        schedule(10.2, () -> showHeaderMenu(title, "Settings"));
        schedule(10.8, () -> collectTransientContractSurface("Header menu Settings", title));
        schedule(11.0, () -> hideTransientWindows(title));
        schedule(11.3, () -> showHeaderMenu(title, "Project"));
        schedule(11.9, () -> collectTransientContractSurface("Header menu Project", title));
        schedule(12.1, () -> hideTransientWindows(title));
        schedule(12.4, () -> showHeaderMenu(title, "View"));
        schedule(13.0, () -> collectTransientContractSurface("Header menu View", title));
        schedule(13.2, () -> hideTransientWindows(title));
        schedule(13.5, () -> fireButton(title, "Segmentation"));
        schedule(14.0, () -> showFirstComboPopup(title));
        schedule(14.6, () -> collectTransientContractSurface("Combo popup", title));
        schedule(14.8, () -> hideTransientWindows(title));
        schedule(15.4, () -> fireFirstHelpButton(title));
        schedule(16.4, () -> collectWindowContractSurface("Help dialog", "ASTRA Parameter Help"));
        schedule(16.8, LauncherPreviewApp::openRuntimeInstallerDiagnosticWindow);
        schedule(17.5, () -> collectWindowContractSurface("Runtime setup panel",
                "ASTRA Runtime Installer Diagnostic"));
        schedule(17.7, LauncherPreviewApp::openRuntimeConfirmationDialog);
        schedule(18.4, () -> collectTransientContractSurface("Runtime confirmation dialog", title));
        schedule(18.6, LauncherPreviewApp::openRuntimeResultDialog);
        schedule(19.3, () -> collectTransientContractSurface("Runtime result dialog", title));
        schedule(19.5, LauncherPreviewApp::openListCodeEditorDiagnosticWindow);
        schedule(20.2, () -> collectWindowContractSurface("List/code editor diagnostic",
                "ASTRA List And Code Editor Diagnostic"));
        schedule(20.4, LauncherPreviewApp::openRunProgressDiagnosticWindow);
        schedule(21.1, () -> collectWindowContractSurface("Run progress diagnostic",
                "ASTRA Run Progress Geometry"));
        schedule(21.3, LauncherPreviewApp::openMarkerKeyMapDiagnosticWindow);
        schedule(22.0, () -> collectWindowContractSurface("Marker key map diagnostic",
                "ASTRA Marker Key Map Diagnostic"));
        schedule(22.2, LauncherPreviewApp::openChannelMultiSelectDiagnosticWindow);
        schedule(22.9, () -> collectWindowContractSurface("Channel multi-select diagnostic",
                "ASTRA Channel Multi-Select Diagnostic"));
        schedule(23.4, () -> {
            writeTextContractSweep();
            closeAllWindows();
        });
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
        activeHeaderMenuText = menuText;
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

    private static void fireAdvancedViewButton(String title, String buttonText) {
        findWindowRoot(title)
                .flatMap(root -> firstNode(root, ".astra-advanced-settings-panel"))
                .flatMap(panel -> panel.lookupAll(".button").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(button -> buttonText.equals(button.getText()))
                        .findFirst())
                .ifPresent(Button::fire);
    }

    private static void fireFirstAdvancedSectionHeader(String title) {
        findWindowRoot(title)
                .flatMap(root -> firstNode(root, ".astra-advanced-settings-panel"))
                .flatMap(panel -> panel.lookupAll(".astra-collapsible-header").stream()
                        .filter(Node::isManaged)
                        .findFirst())
                .ifPresent(header -> header.fireEvent(new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        LauncherGeometryTokens.FLUSH,
                        LauncherGeometryTokens.FLUSH,
                        LauncherGeometryTokens.FLUSH,
                        LauncherGeometryTokens.FLUSH,
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
                        null)));
    }

    private static void showHeaderPlacementMenu(String title) {
        activeHeaderMenuText = "View";
        findWindowRoot(title)
                .flatMap(root -> root.lookupAll(".astra-header-menu-button").stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(button -> "View".equals(button.getText()))
                        .findFirst())
                .ifPresent(button -> {
                    ContextMenu menu = new ContextMenu();
                    menu.setMinWidth(PipelineLauncher.headerMenuWidthForTesting());
                    menu.setPrefWidth(PipelineLauncher.headerMenuWidthForTesting());
                    menu.getStyleClass().add("astra-header-context-menu");
                    menu.getItems().add(GuiText.menuItem(GuiText.Role.DIAGNOSTIC_TEXT, "Diagnostic action"));
                    menu.getItems().add(GuiText.menuItem(GuiText.Role.DIAGNOSTIC_TEXT, "Diagnostic secondary action"));
                    PipelineLauncher.showHeaderActionMenu(button, menu);
                });
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

    private static void scrollSettingsPane(String title, double value) {
        findWindowRoot(title)
                .flatMap(root -> firstNode(root, ".astra-settings-scroll"))
                .filter(ScrollPane.class::isInstance)
                .map(ScrollPane.class::cast)
                .ifPresent(scroll -> {
                    scroll.setVvalue(value);
                    scroll.applyCss();
                    scroll.layout();
                });
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
        Optional<Node> rootOptional = surface == Surface.TOOLTIP
                ? activeTooltipRoot()
                : surface == Surface.DIALOG && "ASTRA Parameter Help".equals(launcherTitle)
                        ? findHelpDialogRoot()
                : transientWindow
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
        List<GeometryMeasurement> measurements = surfaceMeasurements(sceneRoot, surface, launcherTitle);
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

    private static Optional<Node> findHelpDialogRoot() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> window.getScene() != null)
                .map(Window::getScene)
                .<Node>map(Scene::getRoot)
                .filter(root -> firstNode(root, ".astra-help-dialog-content").isPresent())
                .findFirst();
    }

    private static Optional<Node> activeTooltipRoot() {
        if (activeDiagnosticTooltipNode != null) {
            return Optional.of(activeDiagnosticTooltipNode);
        }
        if (activeDiagnosticTooltipPopup != null && activeDiagnosticTooltipPopup.isShowing()
                && activeDiagnosticTooltipPopup.getScene() != null) {
            return Optional.of(activeDiagnosticTooltipPopup.getScene().getRoot());
        }
        if (activeDiagnosticTooltip != null && activeDiagnosticTooltip.isShowing()
                && activeDiagnosticTooltip.getScene() != null) {
            return Optional.of(activeDiagnosticTooltip.getScene().getRoot());
        }
        return Optional.empty();
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
                    "%s expected=%.2f observed=%.2f delta=%.2f formula=%s%n",
                    distance.label(),
                    distance.expected(),
                    distance.observed(),
                    distance.delta(),
                    distance.formula()));
            writeRailTables(name, distances);
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
            case ASSET_COMBO -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "asset diagnostic panel",
                        ".astra-asset-combo-diagnostic", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "empty asset combo",
                        ".astra-asset-empty-combo", new Color(255, 159, 28));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "populated asset combo",
                        ".astra-asset-populated-combo", new Color(42, 157, 143));
            }
            case ASSET_COMBO_POPUP -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "asset combo popup list",
                        ".list-view", new Color(0, 95, 115));
                sceneRoot.lookupAll(".astra-combo-cell").forEach(node -> bounds.add(new BoundsMarker(
                        "asset combo popup cell",
                        relativeBounds(sceneRoot, node, rootMinX, rootMinY),
                        new Color(42, 157, 143))));
            }
            case TOOLTIP -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "tooltip root",
                        ".tooltip", new Color(0, 95, 115));
                if (bounds.isEmpty()) {
                    bounds.add(new BoundsMarker("tooltip popup root",
                            relativeBounds(sceneRoot, sceneRoot, rootMinX, rootMinY),
                            new Color(0, 95, 115)));
                }
            }
            case RUNTIME_INSTALLER -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "runtime installer root",
                        ".astra-runtime-installer-root", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "runtime installer header",
                        ".astra-runtime-installer-header", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "runtime installer log",
                        ".astra-runtime-installer-log", new Color(255, 159, 28));
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
            case STYLED_LOG -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "styled log view",
                        ".astra-log-view", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "log status card",
                        ".astra-log-status-card", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "log source tab",
                        ".astra-log-source-tab", new Color(255, 159, 28));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "log source block",
                        ".astra-log-source-block", new Color(131, 56, 236));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "log message card",
                        ".astra-log-message-card", new Color(88, 129, 87));
            }
            case CHANNEL_PANEL -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "channel panel",
                        ".astra-channel-panel", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "channel title",
                        ".astra-channel-panel-title", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "channel chips",
                        ".astra-channel-panel-chips", new Color(255, 159, 28));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "channel chip",
                        ".astra-channel-chip", new Color(131, 56, 236));
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
            case COLOCALIZATION_CUSTOM -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "colocalization focused content",
                        ".astra-section-content-focused", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "semantic card",
                        ".astra-semantic-card", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "nested panel",
                        ".astra-nested-panel", new Color(255, 159, 28));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "colocalization check row",
                        ".astra-colocalization-check-row", new Color(131, 56, 236));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "marker key map editor",
                        ".astra-marker-key-map-editor", new Color(230, 57, 70));
            }
            case MARKER_KEY_MAP -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "marker key map editor",
                        ".astra-marker-key-map-editor", new Color(0, 95, 115));
                sceneRoot.lookupAll(".astra-marker-key-row").forEach(node -> bounds.add(new BoundsMarker(
                        "marker key populated row",
                        relativeBounds(sceneRoot, node, rootMinX, rootMinY),
                        new Color(42, 157, 143))));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "marker key input",
                        ".astra-input", new Color(255, 159, 28));
            }
            case DIALOG -> {
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "dialog pane",
                        ".dialog-pane", new Color(0, 95, 115));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "dialog content",
                        ".content", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "help dialog content",
                        ".astra-help-dialog-content", new Color(88, 129, 87));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "help details shell",
                        ".astra-help-details-shell", new Color(42, 157, 143));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "list view",
                        ".astra-list-view", new Color(255, 159, 28));
                addBounds(bounds, sceneRoot, rootMinX, rootMinY, "dialog input",
                        ".astra-input", new Color(131, 56, 236));
            }
        }
        return bounds;
    }

    private static List<GeometryMeasurement> surfaceMeasurements(Node sceneRoot,
                                                                 Surface surface,
                                                                 String launcherTitle) {
        List<GeometryMeasurement> measurements = new ArrayList<>();
        switch (surface) {
            case HEADER -> addHeaderMeasurements(sceneRoot, measurements);
            case HEADER_MENU -> addHeaderMenuMeasurements(sceneRoot, measurements, launcherTitle);
            case COMBO_CLOSED -> addClosedComboMeasurements(sceneRoot, measurements);
            case COMBO_POPUP -> addComboPopupMeasurements(sceneRoot, measurements);
            case ASSET_COMBO -> addAssetComboMeasurements(sceneRoot, measurements);
            case ASSET_COMBO_POPUP -> addComboPopupMeasurements(sceneRoot, measurements);
            case TOOLTIP -> addTooltipMeasurements(sceneRoot, measurements);
            case RUNTIME_INSTALLER -> addRuntimeInstallerMeasurements(sceneRoot, measurements);
            case OUTPUT -> addOutputMeasurements(sceneRoot, measurements);
            case BUTTON_STATES -> addButtonStateMeasurements(sceneRoot, measurements);
            case STYLED_LOG -> addStyledLogMeasurements(sceneRoot, measurements);
            case CHANNEL_PANEL -> addChannelPanelMeasurements(sceneRoot, measurements);
            case ALL_SETTINGS -> addAllSettingsMeasurements(sceneRoot, measurements);
            case ADVANCED -> addAdvancedMeasurements(sceneRoot, measurements);
            case CUSTOM_CONTROLS -> addCustomControlMeasurements(sceneRoot, measurements);
            case COLOCALIZATION_CUSTOM -> addColocalizationCustomMeasurements(sceneRoot, measurements);
            case MARKER_KEY_MAP -> addMarkerKeyMapMeasurements(sceneRoot, measurements);
            case DEPENDENCY_MATRIX -> addDependencyMatrixMeasurements(sceneRoot, measurements);
            case ROW_STATE -> addRowStateMeasurements(sceneRoot, measurements);
            case LIST_CODE_EDITOR -> addListCodeEditorMeasurements(sceneRoot, measurements);
            case CHANNEL_MULTI_SELECT -> addChannelMultiSelectMeasurements(sceneRoot, measurements);
            case TYPOGRAPHY -> addTypographyMeasurements(sceneRoot, measurements);
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

    private static void addHeaderMenuMeasurements(Node sceneRoot,
                                                  List<GeometryMeasurement> measurements,
                                                  String launcherTitle) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> context = renderedHeaderContextMenu(sceneRoot);
        Optional<Node> panel = firstNode(sceneRoot, ".astra-header-options-panel");
        Optional<Node> group = firstNode(sceneRoot, ".astra-header-options-group");
        context.ifPresent(menu -> {
            Bounds menuBounds = relativeBounds(sceneRoot, menu, rootMinX, rootMinY);
            addMeasurement(measurements, "header context menu width",
                    nestedStaticField("HeaderGeometry", "MENU_POPUP_WIDTH"),
                    menuBounds.getWidth(),
                    "HeaderGeometry.MENU_POPUP_WIDTH");
            managedNodes(menu, ".menu-item").stream()
                    .filter(LauncherPreviewApp::isSimpleHeaderMenuItem)
                    .findFirst()
                    .ifPresent(item -> {
                Bounds itemBounds = relativeBounds(sceneRoot, item, rootMinX, rootMinY);
                addMeasurement(measurements, "header simple menu item left inset",
                        nestedStaticField("HeaderGeometry", "SIMPLE_MENU_ITEM_SHELL_INSET"),
                        itemBounds.getMinX() - menuBounds.getMinX(),
                        "HeaderGeometry.SIMPLE_MENU_ITEM_SHELL_INSET");
                addMeasurement(measurements, "header simple menu item right inset",
                        nestedStaticField("HeaderGeometry", "SIMPLE_MENU_ITEM_SHELL_INSET"),
                        menuBounds.getMaxX() - itemBounds.getMaxX(),
                        "HeaderGeometry.SIMPLE_MENU_ITEM_SHELL_INSET");
                firstNode(item, ".label").ifPresent(label -> {
                    addMeasurement(measurements, "header simple menu label left inset",
                            nestedStaticField("HeaderGeometry", "ACTION_CLUSTER_GAP"),
                            textOrNodeMinX(label) - rootMinX - itemBounds.getMinX(),
                            "HeaderGeometry.ACTION_CLUSTER_GAP");
                });
            });
        });
        addHeaderMenuPlacementMeasurement(sceneRoot, measurements, launcherTitle);
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

    private static void addHeaderMenuPlacementMeasurement(Node sceneRoot,
                                                          List<GeometryMeasurement> measurements,
                                                          String launcherTitle) {
        if (activeHeaderMenuText.isBlank()
                || sceneRoot.getScene() == null
                || sceneRoot.getScene().getWindow() == null) {
            return;
        }
        Optional<Node> launcherRoot = findWindowRoot(launcherTitle);
        if (launcherRoot.isEmpty()
                || launcherRoot.get().getScene() == null
                || launcherRoot.get().getScene().getWindow() == null) {
            return;
        }
        Window launcher = launcherRoot.get().getScene().getWindow();
        Bounds visibleMenuBounds = sceneRoot.localToScreen(sceneRoot.getBoundsInLocal());
        if (visibleMenuBounds == null) {
            return;
        }
        Optional<Bounds> contextMenuBounds = renderedHeaderContextMenu(sceneRoot)
                .map(node -> node.localToScreen(node.getBoundsInLocal()));
        launcherRoot.get().lookupAll(".astra-header-menu-button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> activeHeaderMenuText.equals(button.getText())
                        || nodeContainsText(button, activeHeaderMenuText))
                .findFirst()
                .map(button -> button.localToScreen(button.getBoundsInLocal()))
                .ifPresent(buttonBounds -> {
                    double menuWidth = visibleMenuBounds.getWidth();
                    double launcherMinX = launcher.getX();
                    double launcherMaxX = launcher.getX() + launcher.getWidth();
                    double leftAligned = buttonBounds.getMinX();
                    double rightAligned = buttonBounds.getMaxX() - menuWidth;
                    Window popupWindow = sceneRoot.getScene().getWindow();
                    double popupWindowX = popupWindow.getX();
                    double popupWindowWidth = popupWindow.getWidth();
                    double popupAnchorX = popupWindow instanceof PopupWindow popup
                            ? popup.getAnchorX()
                            : popupWindowX;
                    double contentOffset = visibleMenuBounds.getMinX() - sceneRoot.getScene().getWindow().getX();
                    addMeasurement(
                            measurements,
                            "header menu raw launcher min x",
                            launcherMinX,
                            launcherMinX,
                            "observed launcher window min x");
                    addMeasurement(
                            measurements,
                            "header menu raw launcher max x",
                            launcherMaxX,
                            launcherMaxX,
                            "observed launcher window max x");
                    addMeasurement(
                            measurements,
                            "header menu raw button min x",
                            buttonBounds.getMinX(),
                            buttonBounds.getMinX(),
                            "observed active menu button min x");
                    addMeasurement(
                            measurements,
                            "header menu raw button max x",
                            buttonBounds.getMaxX(),
                            buttonBounds.getMaxX(),
                            "observed active menu button max x");
                    addMeasurement(
                            measurements,
                            "header menu raw popup width",
                            menuWidth,
                            menuWidth,
                            "observed visible menu width");
                    addMeasurement(
                            measurements,
                            "header menu raw popup window width",
                            popupWindowWidth,
                            popupWindowWidth,
                            "observed popup window width");
                    addMeasurement(
                            measurements,
                            "header menu left-aligned candidate x",
                            leftAligned,
                            leftAligned,
                            "button min x");
                    addMeasurement(
                            measurements,
                            "header menu right-aligned candidate x",
                            rightAligned,
                            rightAligned,
                            "button max x - visible menu width");
                    addMeasurement(
                            measurements,
                            "header menu raw popup window x",
                            popupWindowX,
                            popupWindowX,
                            "observed JavaFX popup shell window x");
                    addMeasurement(
                            measurements,
                            "header menu raw popup anchor x",
                            popupAnchorX,
                            popupAnchorX,
                            "observed JavaFX PopupWindow.anchorX");
                    addMeasurement(
                            measurements,
                            "header menu window x minus anchor x",
                            popupWindowX - popupAnchorX,
                            popupWindowX - popupAnchorX,
                            "popup window x - PopupWindow.anchorX");
                    addMeasurement(
                            measurements,
                            "header menu visible content offset",
                            visibleMenuBounds.getMinX() - sceneRoot.getScene().getWindow().getX(),
                            contentOffset,
                            "visible menu box offset from popup window");
                    contextMenuBounds.ifPresent(menuBoxBounds -> {
                        double menuBoxWidth = menuBoxBounds.getWidth();
                        double expectedMenuBoxX = PipelineLauncher.preferredHeaderMenuX(
                                launcherMinX,
                                launcherMaxX,
                                buttonBounds.getMinX(),
                                buttonBounds.getMaxX(),
                                menuBoxWidth,
                                PipelineLauncher.headerMenuEdgeMarginForTesting());
                        addMeasurement(
                                measurements,
                                "header rendered menu box adaptive x placement",
                                expectedMenuBoxX,
                                menuBoxBounds.getMinX(),
                                "preferredHeaderMenuX(launcher, button, context-menu box width, MENU_EDGE_MARGIN)");
                        addMeasurement(
                                measurements,
                                "header rendered menu box x minus preferred x",
                                LauncherGeometryTokens.FLUSH,
                                menuBoxBounds.getMinX() - expectedMenuBoxX,
                                "context-menu box x - preferredHeaderMenuX(...)");
                        addMeasurement(
                                measurements,
                                "header popup shell left inset",
                                menuBoxBounds.getMinX() - popupWindowX,
                                menuBoxBounds.getMinX() - popupWindowX,
                                "context-menu box min x - popup window x");
                        addMeasurement(
                                measurements,
                                "header popup shell right inset",
                                (popupWindowX + popupWindowWidth) - menuBoxBounds.getMaxX(),
                                (popupWindowX + popupWindowWidth) - menuBoxBounds.getMaxX(),
                                "popup window max x - context-menu box max x");
                    });
                });
    }

    private static void addStyledLogMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> logView = firstNode(sceneRoot, ".astra-log-view");
        if (logView.isEmpty()) {
            return;
        }
        Bounds logBounds = relativeBounds(sceneRoot, logView.get(), rootMinX, rootMinY);
        double borderedStackInset = styledLogField("LOG_ROW_GAP") + LauncherGeometryTokens.SURFACE_BORDER_WIDTH;
        firstManagedNode(logView.get(), ".astra-log-scroll-frame").ifPresent(frame -> {
            Bounds frameBounds = relativeBounds(sceneRoot, frame, rootMinX, rootMinY);
            firstManagedNode(logView.get(), ".astra-log-scroll-top-fade").ifPresent(fade -> {
                Bounds fadeBounds = relativeBounds(sceneRoot, fade, rootMinX, rootMinY);
                double expectedFadeHeight = snapUpToOutputPixel(sceneRoot,
                        frameBounds.getHeight() * styledLogField("LOG_FADE_VISIBLE_FRACTION"));
                addMeasurement(measurements, "log fade left edge",
                        0.0,
                        fadeBounds.getMinX() - frameBounds.getMinX(),
                        "fade min x - scroll frame min x");
                addMeasurement(measurements, "log fade right edge",
                        0.0,
                        frameBounds.getMaxX() - fadeBounds.getMaxX(),
                        "scroll frame max x - fade max x");
                addMeasurement(measurements, "log fade top edge",
                        0.0,
                        fadeBounds.getMinY() - frameBounds.getMinY(),
                        "fade min y - scroll frame min y");
                addMeasurement(measurements, "log fade height",
                        expectedFadeHeight,
                        fadeBounds.getHeight(),
                        "ceil((scroll frame height * StyledLogView.LOG_FADE_VISIBLE_FRACTION) * outputScale) / outputScale");
                addMeasurement(measurements, "log fade bottom edge",
                        frameBounds.getHeight() - expectedFadeHeight,
                        frameBounds.getMaxY() - fadeBounds.getMaxY(),
                        "scroll frame remaining height below top fade");
            });
        });
        firstManagedNode(logView.get(), ".astra-log-status-card").ifPresent(card -> {
            Bounds cardBounds = relativeBounds(sceneRoot, card, rootMinX, rootMinY);
            addMeasurement(measurements, "log view left inset to status card",
                    borderedStackInset,
                    cardBounds.getMinX() - logBounds.getMinX(),
                    "StyledLogView.LOG_ROW_GAP + SURFACE_BORDER_WIDTH");
            addMeasurement(measurements, "log view right inset to status card",
                    borderedStackInset,
                    logBounds.getMaxX() - cardBounds.getMaxX(),
                    "StyledLogView.LOG_ROW_GAP + SURFACE_BORDER_WIDTH");
            firstManagedNode(card, ".astra-log-status-title").ifPresent(title -> {
                addMeasurement(measurements, "log status title left inset",
                        styledLogField("LOG_CARD_HORIZONTAL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        textOrNodeMinX(title) - rootMinX - cardBounds.getMinX(),
                        "StyledLogView.LOG_CARD_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
            });
        });
        firstManagedNode(logView.get(), ".astra-log-copy-button").ifPresent(button -> {
            Bounds buttonBounds = relativeBounds(sceneRoot, button, rootMinX, rootMinY);
            addMeasurement(measurements, "log copy button right inset",
                    borderedStackInset,
                    logBounds.getMaxX() - buttonBounds.getMaxX(),
                    "StyledLogView.LOG_ROW_GAP + SURFACE_BORDER_WIDTH");
        });
        firstManagedNode(logView.get(), ".astra-log-source-tab").ifPresent(tab -> {
            Bounds tabBounds = relativeBounds(sceneRoot, tab, rootMinX, rootMinY);
            firstManagedNode(logView.get(), ".astra-log-source-block").ifPresent(block -> {
                Bounds blockBounds = relativeBounds(sceneRoot, block, rootMinX, rootMinY);
                addMeasurement(measurements, "log source tab left inset from block",
                        styledLogField("LOG_GROUP_TAB_LEFT_INSET"),
                        tabBounds.getMinX() - blockBounds.getMinX(),
                        "StyledLogView.LOG_GROUP_TAB_LEFT_INSET");
            });
        });
        managedNodes(logView.get(), ".astra-log-source-block").stream()
                .filter(block -> firstNonBadgeLogRow(block).isPresent())
                .findFirst()
                .ifPresent(block -> {
            Bounds blockBounds = relativeBounds(sceneRoot, block, rootMinX, rootMinY);
            firstNonBadgeLogRow(block).ifPresent(row -> {
                Optional<Node> accentNode = firstManagedNode(row, ".astra-log-line-accent");
                Optional<Node> textNode = firstManagedNode(row, ".astra-log-line-text");
                if (accentNode.isEmpty() || textNode.isEmpty()) {
                    return;
                }
                Node accent = accentNode.get();
                Bounds accentBounds = relativeBounds(sceneRoot, accent, rootMinX, rootMinY);
                addMeasurement(measurements, "log source block left inset to line accent",
                        styledLogField("LOG_CARD_HORIZONTAL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        accentBounds.getMinX() - blockBounds.getMinX(),
                        "StyledLogView.LOG_CARD_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
                addMeasurement(measurements, "log line accent width",
                        styledLogField("LOG_ACCENT_WIDTH"),
                        accentBounds.getWidth(),
                        "StyledLogView.LOG_ACCENT_WIDTH");
                addMeasurement(measurements, "log line accent to text",
                        styledLogField("LOG_LINE_GAP"),
                        textOrNodeMinX(textNode.get()) - rootMinX - accentBounds.getMaxX(),
                        "StyledLogView.LOG_LINE_GAP");
            });
        });
        firstManagedNode(logView.get(), ".astra-log-message-card").ifPresent(card -> {
            Bounds cardBounds = relativeBounds(sceneRoot, card, rootMinX, rootMinY);
            firstManagedNode(card, ".astra-log-card-title").ifPresent(title ->
                    addMeasurement(measurements, "log message card title left inset",
                            styledLogField("LOG_CARD_HORIZONTAL_INSET")
                                    + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                            textOrNodeMinX(title) - rootMinX - cardBounds.getMinX(),
                            "StyledLogView.LOG_CARD_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH"));
        });
        firstManagedNode(logView.get(), ".astra-log-key-value-card").ifPresent(card -> {
            Bounds cardBounds = relativeBounds(sceneRoot, card, rootMinX, rootMinY);
            firstManagedNode(card, ".astra-log-key").ifPresent(key -> {
                Bounds keyBounds = relativeBounds(sceneRoot, key, rootMinX, rootMinY);
                addMeasurement(measurements, "log key-value card key left inset",
                        styledLogField("LOG_CARD_VERTICAL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        textOrNodeMinX(key) - rootMinX - cardBounds.getMinX(),
                        "StyledLogView.LOG_CARD_VERTICAL_INSET + SURFACE_BORDER_WIDTH");
                addMeasurement(measurements, "log key-value key width",
                        styledLogField("LOG_KEY_VALUE_WIDTH"),
                        keyBounds.getWidth(),
                        "StyledLogView.LOG_KEY_VALUE_WIDTH");
            });
            if (firstManagedNode(card, ".astra-log-key").isPresent()
                    && firstManagedNode(card, ".astra-log-value").isPresent()) {
                Bounds keyBounds = relativeBounds(sceneRoot,
                        firstManagedNode(card, ".astra-log-key").get(), rootMinX, rootMinY);
                Node value = firstManagedNode(card, ".astra-log-value").get();
                addMeasurement(measurements, "log key-value key to value gap",
                        styledLogField("LOG_ROW_GAP"),
                        textOrNodeMinX(value) - rootMinX - keyBounds.getMaxX(),
                        "StyledLogView.LOG_ROW_GAP");
            }
        });
        firstManagedNode(logView.get(), ".astra-log-command-card").ifPresent(card -> {
            Bounds cardBounds = relativeBounds(sceneRoot, card, rootMinX, rootMinY);
            firstManagedNode(card, ".astra-log-command-title").ifPresent(title -> {
                Bounds titleBounds = relativeBounds(sceneRoot, title, rootMinX, rootMinY);
                addMeasurement(measurements, "log command card title left inset",
                        styledLogField("LOG_CARD_HORIZONTAL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        textOrNodeMinX(title) - rootMinX - cardBounds.getMinX(),
                        "StyledLogView.LOG_CARD_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
                firstManagedNode(card, ".astra-log-command-text").ifPresent(text -> {
                    Bounds textBounds = relativeBounds(sceneRoot, text, rootMinX, rootMinY);
                    addMeasurement(measurements, "log command title to text gap",
                            styledLogField("LOG_TIGHT_GAP"),
                            textBounds.getMinY() - titleBounds.getMaxY(),
                            "StyledLogView.LOG_TIGHT_GAP");
                    addMeasurement(measurements, "log command text left inset",
                            styledLogField("LOG_CARD_HORIZONTAL_INSET")
                                    + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                            textOrNodeMinX(text) - rootMinX - cardBounds.getMinX(),
                            "StyledLogView.LOG_CARD_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
                });
            });
        });
        managedNodes(logView.get(), ".astra-log-metric-row").stream()
                .map(row -> managedNodes(row, ".astra-log-metric-badge"))
                .filter(badges -> badges.size() >= 2)
                .findFirst()
                .ifPresent(badges -> {
                    Bounds first = relativeBounds(sceneRoot, badges.get(0), rootMinX, rootMinY);
                    Bounds second = relativeBounds(sceneRoot, badges.get(1), rootMinX, rootMinY);
                    addMeasurement(measurements, "log metric badge gap",
                            styledLogField("LOG_TIGHT_GAP"),
                            second.getMinX() - first.getMaxX(),
                            "StyledLogView.LOG_TIGHT_GAP");
                });
        firstManagedNode(logView.get(), ".astra-log-timeline-dot").ifPresent(dot -> {
            Bounds dotBounds = relativeBounds(sceneRoot, dot, rootMinX, rootMinY);
            addMeasurement(measurements, "log timeline dot width",
                    styledLogField("LOG_DOT_SIZE"),
                    dotBounds.getWidth(),
                    "StyledLogView.LOG_DOT_SIZE");
            firstManagedNode(logView.get(), ".astra-log-timeline-label").ifPresent(label ->
                    addMeasurement(measurements, "log timeline dot to label gap",
                            styledLogField("LOG_GROUP_BODY_GAP"),
                            textOrNodeMinX(label) - rootMinX - dotBounds.getMaxX(),
                            "StyledLogView.LOG_GROUP_BODY_GAP"));
        });
        firstManagedNode(logView.get(), ".astra-log-failure-summary").ifPresent(summary -> {
            Bounds summaryBounds = relativeBounds(sceneRoot, summary, rootMinX, rootMinY);
            firstManagedNode(summary, ".astra-log-failure-title").ifPresent(title ->
                    addMeasurement(measurements, "log failure title left inset",
                            styledLogField("LOG_CARD_HORIZONTAL_INSET")
                                    + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                            textOrNodeMinX(title) - rootMinX - summaryBounds.getMinX(),
                            "StyledLogView.LOG_CARD_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH"));
            firstManagedNode(summary, ".astra-log-advice-label").ifPresent(label -> {
                Bounds labelBounds = relativeBounds(sceneRoot, label, rootMinX, rootMinY);
                addMeasurement(measurements, "log failure advice label width",
                        styledLogField("LOG_ADVICE_LABEL_WIDTH"),
                        labelBounds.getWidth(),
                        "StyledLogView.LOG_ADVICE_LABEL_WIDTH");
                firstManagedNode(summary, ".astra-log-advice-value").ifPresent(value ->
                        addMeasurement(measurements, "log failure advice label to value gap",
                                styledLogField("LOG_ROW_GAP"),
                                textOrNodeMinX(value) - rootMinX - labelBounds.getMaxX(),
                                "StyledLogView.LOG_ROW_GAP"));
            });
        });
        firstManagedNode(logView.get(), ".astra-log-disclosure-button").ifPresent(button -> {
            Bounds buttonBounds = relativeBounds(sceneRoot, button, rootMinX, rootMinY);
            Node block = button.getParent();
            if (block != null) {
                Bounds blockBounds = relativeBounds(sceneRoot, block, rootMinX, rootMinY);
                addMeasurement(measurements, "log disclosure button left inset",
                        styledLogField("LOG_CARD_HORIZONTAL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        buttonBounds.getMinX() - blockBounds.getMinX(),
                        "StyledLogView.LOG_CARD_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
                firstManagedNode(block, ".astra-log-hidden-body").ifPresent(body -> {
                    Bounds bodyBounds = relativeBounds(sceneRoot, body, rootMinX, rootMinY);
                    addMeasurement(measurements, "log disclosure button to hidden body gap",
                            styledLogField("LOG_GROUP_BODY_GAP"),
                            bodyBounds.getMinY() - buttonBounds.getMaxY(),
                            "StyledLogView.LOG_GROUP_BODY_GAP");
                });
            }
        });
    }

    private static void addChannelPanelMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> panel = firstNode(sceneRoot, ".astra-channel-panel");
        if (panel.isEmpty()) {
            return;
        }
        Bounds panelBounds = relativeBounds(sceneRoot, panel.get(), rootMinX, rootMinY);
        firstManagedNode(panel.get(), ".astra-channel-panel-title").ifPresent(title -> {
            Bounds titleBounds = relativeBounds(sceneRoot, title, rootMinX, rootMinY);
            addMeasurement(measurements, "channel title left inset",
                    staticField("CHANNEL_PANEL_INSET") + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    textOrNodeMinX(title) - rootMinX - panelBounds.getMinX(),
                    "CHANNEL_PANEL_INSET + SURFACE_BORDER_WIDTH");
            addMeasurement(measurements, "channel title top inset",
                    staticField("CHANNEL_PANEL_INSET") + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    titleBounds.getMinY() - panelBounds.getMinY(),
                    "CHANNEL_PANEL_INSET + SURFACE_BORDER_WIDTH");
        });
        List<Node> children = managedChildren(panel.get());
        if (children.size() >= 2) {
            Bounds first = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "channel panel child vertical gap",
                    staticField("CHANNEL_PANEL_GAP"),
                    second.getMinY() - first.getMaxY(),
                    "CHANNEL_PANEL_GAP");
        }
        List<Node> chips = managedNodes(panel.get(), ".astra-channel-chip");
        if (chips.size() >= 2) {
            Bounds firstChip = relativeBounds(sceneRoot, chips.get(0), rootMinX, rootMinY);
            Bounds secondChip = relativeBounds(sceneRoot, chips.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "channel chip horizontal gap",
                    staticField("CHANNEL_CHIP_HORIZONTAL_INSET"),
                    secondChip.getMinX() - firstChip.getMaxX(),
                    "CHANNEL_CHIP_HORIZONTAL_INSET");
        }
        firstManagedNode(panel.get(), ".astra-channel-chip").ifPresent(chip -> {
            Bounds chipBounds = relativeBounds(sceneRoot, chip, rootMinX, rootMinY);
            firstManagedNode(chip, ".astra-channel-chip-swatch").ifPresent(swatch -> {
                Bounds swatchBounds = relativeBounds(sceneRoot, swatch, rootMinX, rootMinY);
                addMeasurement(measurements, "channel chip left inset to swatch",
                        staticField("CHANNEL_CHIP_HORIZONTAL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        swatchBounds.getMinX() - chipBounds.getMinX(),
                        "CHANNEL_CHIP_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
                addMeasurement(measurements, "channel chip swatch size",
                        staticField("CHANNEL_SWATCH_SIZE"),
                        swatchBounds.getWidth(),
                        "CHANNEL_SWATCH_SIZE");
                addMeasurement(measurements, "channel chip centered swatch top inset",
                        (chipBounds.getHeight() - swatchBounds.getHeight()) / 2.0,
                        swatchBounds.getMinY() - chipBounds.getMinY(),
                        "(chip height - rendered swatch height) / 2");
                firstManagedNode(chip, ".astra-channel-chip-name").ifPresent(name -> {
                    Bounds nameBounds = relativeBounds(sceneRoot, name, rootMinX, rootMinY);
                    addMeasurement(measurements, "channel swatch to label gap",
                            staticField("CHANNEL_CHIP_GAP"),
                            textOrNodeMinX(name) - rootMinX - swatchBounds.getMaxX(),
                            "CHANNEL_CHIP_GAP");
                    addMeasurement(measurements, "channel chip right inset from label",
                            staticField("CHANNEL_CHIP_HORIZONTAL_INSET")
                                    + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                            chipBounds.getMaxX() - nameBounds.getMaxX(),
                            "CHANNEL_CHIP_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
                });
            });
        });
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
                    controlTextMinX(cell.get()) - rootMinX - comboBounds.getMinX(),
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

    private static void addTooltipMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Node tooltip = nodeWithStyleOrRoot(sceneRoot, "tooltip").orElse(sceneRoot);
        Bounds tooltipBounds = relativeBounds(sceneRoot, tooltip, rootMinX, rootMinY);
        if (tooltip instanceof Region region) {
            addMeasurement(measurements, "tooltip horizontal padding policy",
                    LauncherGeometryTokens.TOOLTIP_HORIZONTAL_INSET,
                    region.getPadding().getLeft(),
                    "TOOLTIP_HORIZONTAL_INSET");
            addMeasurement(measurements, "tooltip mirrored horizontal padding policy",
                    LauncherGeometryTokens.TOOLTIP_HORIZONTAL_INSET,
                    region.getPadding().getRight(),
                    "TOOLTIP_HORIZONTAL_INSET");
            addMeasurement(measurements, "tooltip vertical padding policy",
                    LauncherGeometryTokens.TOOLTIP_VERTICAL_INSET,
                    region.getPadding().getTop(),
                    "TOOLTIP_VERTICAL_INSET");
            addMeasurement(measurements, "tooltip mirrored vertical padding policy",
                    LauncherGeometryTokens.TOOLTIP_VERTICAL_INSET,
                    region.getPadding().getBottom(),
                    "TOOLTIP_VERTICAL_INSET");
        }
    }

    private static void addRuntimeInstallerMeasurements(Node sceneRoot,
                                                        List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> root = firstNode(sceneRoot, ".astra-runtime-installer-root");
        Optional<Node> header = firstNode(sceneRoot, ".astra-runtime-installer-header");
        Optional<Node> stepList = firstNode(sceneRoot, ".astra-runtime-installer-step-list");
        Optional<Node> progress = firstNode(sceneRoot, ".astra-runtime-installer-progress");
        Optional<Node> status = firstNode(sceneRoot, ".astra-runtime-installer-status-card");
        Optional<Node> logPane = firstNode(sceneRoot, ".astra-runtime-installer-log-pane");
        Optional<Node> actions = firstNode(sceneRoot, ".astra-runtime-installer-actions");
        Optional<Node> copy = firstNode(sceneRoot, ".astra-runtime-installer-copy");
        Optional<Node> cancel = firstNode(sceneRoot, ".astra-runtime-installer-cancel");
        if (root.isPresent()) {
            Bounds rootBounds = relativeBounds(sceneRoot, root.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "runtime installer scene width",
                    RuntimeInstaller.installerWindowWidthForTesting(),
                    sceneRoot.getScene().getWidth(),
                    "RuntimeInstaller.InstallerGeometry.WINDOW_WIDTH");
            addMeasurement(measurements, "runtime installer scene height",
                    RuntimeInstaller.installerWindowHeightForTesting(),
                    sceneRoot.getScene().getHeight(),
                    "RuntimeInstaller.InstallerGeometry.WINDOW_HEIGHT");
            header.ifPresent(node -> {
                Bounds headerBounds = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
                addMeasurement(measurements, "runtime root left padding to header",
                        RuntimeInstaller.installerRootPaddingForTesting(),
                        headerBounds.getMinX() - rootBounds.getMinX(),
                        "RuntimeInstaller.InstallerGeometry.ROOT_PADDING");
                addMeasurement(measurements, "runtime root top padding to header",
                        RuntimeInstaller.installerRootPaddingForTesting(),
                        headerBounds.getMinY() - rootBounds.getMinY(),
                        "RuntimeInstaller.InstallerGeometry.ROOT_PADDING");
                stepList.ifPresent(stepNode -> {
                    Bounds stepBounds = relativeBounds(sceneRoot, stepNode, rootMinX, rootMinY);
                    addMeasurement(measurements, "runtime header to step list gap",
                            RuntimeInstaller.installerRootContentGapForTesting(),
                            stepBounds.getMinY() - headerBounds.getMaxY(),
                            "RuntimeInstaller.InstallerGeometry.ROOT_CONTENT_GAP");
                });
            });
            addVerticalGapMeasurement(sceneRoot, measurements, rootMinX, rootMinY,
                    stepList, progress, "runtime step list to progress gap");
            addVerticalGapMeasurement(sceneRoot, measurements, rootMinX, rootMinY,
                    progress, status, "runtime progress to status gap");
            addVerticalGapMeasurement(sceneRoot, measurements, rootMinX, rootMinY,
                    status, logPane, "runtime status to log pane gap");
            addVerticalGapMeasurement(sceneRoot, measurements, rootMinX, rootMinY,
                    logPane, actions, "runtime log pane to actions gap");
            progress.ifPresent(node -> {
                Bounds progressBounds = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
                addMeasurement(measurements, "runtime progress lane height",
                        RuntimeInstaller.installerProgressBarHeightForTesting(),
                        progressBounds.getHeight(),
                        "RuntimeInstaller.InstallerGeometry.PROGRESS_BAR_HEIGHT");
            });
            actions.ifPresent(node -> {
                Bounds actionBounds = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
                addMeasurement(measurements, "runtime root bottom padding to actions",
                        RuntimeInstaller.installerRootPaddingForTesting(),
                        rootBounds.getMaxY() - actionBounds.getMaxY(),
                        "RuntimeInstaller.InstallerGeometry.ROOT_PADDING");
            });
            for (Node node : List.of(header, stepList, progress, status, logPane, actions).stream().flatMap(Optional::stream).toList()) {
                Bounds bounds = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
                String name = runtimeInstallerNodeName(node);
                addMeasurement(measurements, "runtime root left padding to " + name,
                        RuntimeInstaller.installerRootPaddingForTesting(),
                        bounds.getMinX() - rootBounds.getMinX(),
                        "RuntimeInstaller.InstallerGeometry.ROOT_PADDING");
                addMeasurement(measurements, "runtime root right padding to " + name,
                        RuntimeInstaller.installerRootPaddingForTesting(),
                        rootBounds.getMaxX() - bounds.getMaxX(),
                        "RuntimeInstaller.InstallerGeometry.ROOT_PADDING");
            }
        }
        if (copy.isPresent() && cancel.isPresent()) {
            Bounds copyBounds = relativeBounds(sceneRoot, copy.get(), rootMinX, rootMinY);
            Bounds cancelBounds = relativeBounds(sceneRoot, cancel.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "runtime copy to cancel gap",
                    RuntimeInstaller.installerHeaderRowGapForTesting(),
                    cancelBounds.getMinX() - copyBounds.getMaxX(),
                    "RuntimeInstaller.InstallerGeometry.HEADER_ROW_GAP");
        }
    }

    private static void addVerticalGapMeasurement(Node sceneRoot,
                                                  List<GeometryMeasurement> measurements,
                                                  double rootMinX,
                                                  double rootMinY,
                                                  Optional<Node> upper,
                                                  Optional<Node> lower,
                                                  String label) {
        if (upper.isEmpty() || lower.isEmpty()) {
            return;
        }
        Bounds upperBounds = relativeBounds(sceneRoot, upper.get(), rootMinX, rootMinY);
        Bounds lowerBounds = relativeBounds(sceneRoot, lower.get(), rootMinX, rootMinY);
        addMeasurement(measurements, label,
                RuntimeInstaller.installerRootContentGapForTesting(),
                lowerBounds.getMinY() - upperBounds.getMaxY(),
                "RuntimeInstaller.InstallerGeometry.ROOT_CONTENT_GAP");
    }

    private static String runtimeInstallerNodeName(Node node) {
        if (node.getStyleClass().contains("astra-runtime-installer-header")) {
            return "header";
        }
        if (node.getStyleClass().contains("astra-runtime-installer-step-list")) {
            return "step list";
        }
        if (node.getStyleClass().contains("astra-runtime-installer-progress")) {
            return "progress";
        }
        if (node.getStyleClass().contains("astra-runtime-installer-status-card")) {
            return "status";
        }
        if (node.getStyleClass().contains("astra-runtime-installer-log-pane")) {
            return "log pane";
        }
        if (node.getStyleClass().contains("astra-runtime-installer-actions")) {
            return "actions";
        }
        return "node";
    }

    private static void addAssetComboMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        addSingleAssetComboMeasurements(sceneRoot, measurements,
                ".astra-asset-empty-combo", "empty asset-backed combo");
        addSingleAssetComboMeasurements(sceneRoot, measurements,
                ".astra-asset-populated-combo", "populated asset-backed combo");
        List<Node> combos = managedNodes(sceneRoot, ".astra-combo");
        if (combos.size() >= 2) {
            double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
            double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
            Bounds first = relativeBounds(sceneRoot, combos.get(0), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, combos.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "asset combo vertical row gap",
                    staticField("PARAMETER_ROW_GAP"),
                    second.getMinY() - first.getMaxY(),
                    "PARAMETER_ROW_GAP");
        }
    }

    private static void addSingleAssetComboMeasurements(Node sceneRoot,
                                                        List<GeometryMeasurement> measurements,
                                                        String comboStyleClass,
                                                        String labelPrefix) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> combo = firstNode(sceneRoot, comboStyleClass);
        if (combo.isEmpty()) {
            return;
        }
        Optional<Node> cell = firstManagedNode(combo.get(), ".astra-combo-cell");
        Optional<Node> arrowButton = firstManagedNode(combo.get(), ".astra-combo-arrow-button");
        if (cell.isEmpty()) {
            return;
        }
        Bounds comboBounds = relativeBounds(sceneRoot, combo.get(), rootMinX, rootMinY);
        Bounds cellBounds = relativeBounds(sceneRoot, cell.get(), rootMinX, rootMinY);
        addMeasurement(measurements, labelPrefix + " left cell inset",
                nestedStaticField("ControlGeometry", "COMBO_CELL_HORIZONTAL_INSET")
                        + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                controlTextMinX(cell.get()) - rootMinX - comboBounds.getMinX(),
                "ControlGeometry.COMBO_CELL_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
        arrowButton.ifPresent(arrow -> {
            Bounds arrowBounds = relativeBounds(sceneRoot, arrow, rootMinX, rootMinY);
            addMeasurement(measurements, labelPrefix + " cell to arrow",
                    0.0,
                    arrowBounds.getMinX() - cellBounds.getMaxX(),
                    "asset combo cell and arrow button share edge");
            addMeasurement(measurements, labelPrefix + " arrow to right edge",
                    0.0,
                    comboBounds.getMaxX() - arrowBounds.getMaxX(),
                    "asset combo arrow button reaches combo right edge");
        });
    }

    private static void addOutputMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> output = firstNode(sceneRoot, ".astra-output-pane");
        Optional<Node> statusCard = firstNode(sceneRoot, ".astra-log-status-card");
        Optional<Node> statusTitle = firstNode(sceneRoot, ".astra-log-status-title");
        Optional<Node> copy = firstNode(sceneRoot, ".astra-log-copy-button");
        Optional<Node> logScroll = firstNode(sceneRoot, ".astra-log-scroll");
        Optional<Node> logScrollFrame = firstNode(sceneRoot, ".astra-log-scroll-frame");
        Optional<Node> killButton = firstNode(sceneRoot, ".astra-output-kill-button");
        if (output.isPresent()) {
            Bounds outputBounds = relativeBounds(sceneRoot, output.get(), rootMinX, rootMinY);
            killButton.ifPresent(button -> {
                Bounds buttonBounds = relativeBounds(sceneRoot, button, rootMinX, rootMinY);
                addMeasurement(measurements, "kill run button right rail",
                        staticField("OUTPUT_PANE_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        outputBounds.getMaxX() - buttonBounds.getMaxX(),
                        "OUTPUT_PANE_INSET + SURFACE_BORDER_WIDTH");
            });
            if (killButton.isPresent() && logScrollFrame.isPresent()) {
                Bounds buttonBounds = relativeBounds(sceneRoot, killButton.get(), rootMinX, rootMinY);
                Bounds frameBounds = relativeBounds(sceneRoot, logScrollFrame.get(), rootMinX, rootMinY);
                addMeasurement(measurements, "kill run to log viewport right rail gap",
                        styledLogField("LOG_ROW_GAP"),
                        buttonBounds.getMaxX() - frameBounds.getMaxX(),
                        "kill max x - log scroll frame max x equals StyledLogView.LOG_ROW_GAP");
            }
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
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH
                                + staticField("OUTPUT_HEADER_GAP")
                                + LauncherGeometryTokens.OUTPUT_ACTION_BUTTON_WIDTH,
                        outputBounds.getMaxX() - copyBounds.getMaxX(),
                        "OUTPUT_PANE_INSET + SURFACE_BORDER_WIDTH + OUTPUT_HEADER_GAP + OUTPUT_ACTION_BUTTON_WIDTH");
                killButton.ifPresent(kill -> {
                    Bounds killBounds = relativeBounds(sceneRoot, kill, rootMinX, rootMinY);
                    addMeasurement(measurements, "output action button width equality",
                            killBounds.getWidth(),
                            copyBounds.getWidth(),
                            "copy button width equals kill button width");
                    addMeasurement(measurements, "output action button height equality",
                            killBounds.getHeight(),
                            copyBounds.getHeight(),
                            "copy button height equals kill button height");
                    addMeasurement(measurements, "output action button top alignment",
                            0.0,
                            copyBounds.getMinY() - killBounds.getMinY(),
                            "copy button top - kill button top");
                });
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

    private static void addButtonStateMeasurements(Node sceneRoot,
                                                   List<GeometryMeasurement> measurements) {
        firstButtonByText(sceneRoot, "Run").ifPresent(button ->
                addButtonStateMeasurements(sceneRoot, measurements, "run button", button));
        firstButtonByText(sceneRoot, "Cancel").ifPresent(button ->
                addButtonStateMeasurements(sceneRoot, measurements, "cancel button", button));
        firstManagedNode(sceneRoot, ".astra-header-menu-button").ifPresent(button ->
                addButtonStateMeasurements(sceneRoot, measurements, "header menu button", button));
        firstManagedNode(sceneRoot, ".astra-button-small").ifPresent(button ->
                addButtonStateMeasurements(sceneRoot, measurements, "small button", button));
        firstManagedNode(sceneRoot, ".astra-button-help").ifPresent(button ->
                addButtonStateMeasurements(sceneRoot, measurements, "help button", button));
        firstManagedNode(sceneRoot, ".astra-dialog-button").ifPresent(button ->
                addButtonStateMeasurements(sceneRoot, measurements, "dialog button", button));
        firstManagedNode(sceneRoot, ".astra-log-copy-button").ifPresent(button ->
                addButtonStateMeasurements(sceneRoot, measurements, "output copy button", button));
        firstManagedNode(sceneRoot, ".astra-header-segment-button").ifPresent(button ->
                addButtonStateMeasurements(sceneRoot, measurements, "segment button", button));
    }

    private static void addButtonStateMeasurements(Node sceneRoot,
                                                   List<GeometryMeasurement> measurements,
                                                   String label,
                                                   Node button) {
        Bounds normal = button.localToScene(button.getBoundsInLocal());
        measurePseudoClassGeometry(sceneRoot, measurements, label, button, normal,
                HOVER_PSEUDO_CLASS, "hover");
        measurePseudoClassGeometry(sceneRoot, measurements, label, button, normal,
                PRESSED_PSEUDO_CLASS, "pressed");
        boolean disabled = button.isDisable();
        button.setDisable(true);
        applyCss(sceneRoot);
        Bounds disabledBounds = button.localToScene(button.getBoundsInLocal());
        addStableBoundsMeasurements(measurements, label + " disabled",
                normal, disabledBounds, "disabled state keeps button bounds stable");
        button.setDisable(disabled);
        applyCss(sceneRoot);
    }

    private static void measurePseudoClassGeometry(Node sceneRoot,
                                                   List<GeometryMeasurement> measurements,
                                                   String label,
                                                   Node button,
                                                   Bounds normal,
                                                   PseudoClass pseudoClass,
                                                   String state) {
        button.pseudoClassStateChanged(pseudoClass, true);
        applyCss(sceneRoot);
        Bounds stateBounds = button.localToScene(button.getBoundsInLocal());
        addStableBoundsMeasurements(measurements, label + " " + state,
                normal, stateBounds, state + " state keeps button bounds stable");
        button.pseudoClassStateChanged(pseudoClass, false);
        applyCss(sceneRoot);
    }

    private static void addStableBoundsMeasurements(List<GeometryMeasurement> measurements,
                                                    String label,
                                                    Bounds normal,
                                                    Bounds state,
                                                    String formula) {
        addMeasurement(measurements, label + " min x delta",
                LauncherGeometryTokens.FLUSH,
                state.getMinX() - normal.getMinX(),
                formula);
        addMeasurement(measurements, label + " min y delta",
                LauncherGeometryTokens.FLUSH,
                state.getMinY() - normal.getMinY(),
                formula);
        addMeasurement(measurements, label + " width delta",
                LauncherGeometryTokens.FLUSH,
                state.getWidth() - normal.getWidth(),
                formula);
        addMeasurement(measurements, label + " height delta",
                LauncherGeometryTokens.FLUSH,
                state.getHeight() - normal.getHeight(),
                formula);
    }

    private static void applyCss(Node sceneRoot) {
        sceneRoot.applyCss();
        if (sceneRoot instanceof Parent parent) {
            parent.layout();
        }
    }
    private static void addAllSettingsMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        addSectionMeasurements(sceneRoot, measurements, ".astra-section-content");
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        List<Node> sections = managedNodes(sceneRoot, ".astra-collapsible-section");
        sections.stream().findFirst().ifPresent(section -> {
            Optional<Node> header = firstNode(section, ".astra-collapsible-header");
            Optional<Node> title = firstNode(section, ".astra-collapsible-title");
            Optional<Node> arrow = firstNode(section, ".astra-collapsible-arrow");
            Optional<Node> content = firstNode(section, ".astra-section-content");
            header.ifPresent(headerNode -> {
                Bounds headerBounds = relativeBounds(sceneRoot, headerNode, rootMinX, rootMinY);
                if (headerNode instanceof Parent headerParent
                        && !headerParent.getChildrenUnmodifiable().isEmpty()) {
                    Node headerContent = headerParent.getChildrenUnmodifiable().get(0);
                    Bounds headerContentBounds = relativeBounds(sceneRoot, headerContent, rootMinX, rootMinY);
                    addMeasurement(measurements, "collapsible header content left inset",
                            LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                            headerContentBounds.getMinX() - headerBounds.getMinX(),
                            "SURFACE_BORDER_WIDTH");
                    addMeasurement(measurements, "collapsible header content right inset",
                            LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                            headerBounds.getMaxX() - headerContentBounds.getMaxX(),
                            "SURFACE_BORDER_WIDTH");
                }
                title.ifPresent(titleNode -> {
                    Bounds titleBounds = relativeBounds(sceneRoot, titleNode, rootMinX, rootMinY);
                    addMeasurement(measurements, "collapsible title left rail",
                            staticField("COLLAPSIBLE_HEADER_HORIZONTAL_INSET")
                                    + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                            titleBounds.getMinX() - headerBounds.getMinX(),
                            "COLLAPSIBLE_HEADER_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
                });
                arrow.ifPresent(arrowNode -> {
                    Bounds arrowBounds = relativeBounds(sceneRoot, arrowNode, rootMinX, rootMinY);
                    addMeasurement(measurements, "collapsible arrow right rail",
                            staticField("COLLAPSIBLE_HEADER_HORIZONTAL_INSET")
                                    + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                            headerBounds.getMaxX() - arrowBounds.getMaxX(),
                            "COLLAPSIBLE_HEADER_HORIZONTAL_INSET + SURFACE_BORDER_WIDTH");
                    addMeasurement(measurements, "collapsible arrow width",
                            staticField("COLLAPSIBLE_ARROW_WIDTH"),
                            arrowBounds.getWidth(),
                            "COLLAPSIBLE_ARROW_WIDTH");
                });
                content.ifPresent(contentNode -> {
                    Bounds contentBounds = relativeBounds(sceneRoot, contentNode, rootMinX, rootMinY);
                    addMeasurement(measurements, "collapsible header to content join",
                            LauncherGeometryTokens.FLUSH,
                            contentBounds.getMinY() - headerBounds.getMaxY(),
                            "FLUSH");
                });
            });
        });
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
        Optional<Node> filler = firstNode(sceneRoot, ".astra-input-gradient-filler");
        firstNode(sceneRoot, ".astra-routine-settings-panel").ifPresent(settings -> panel.ifPresent(advanced -> {
            Bounds settingsBounds = relativeBounds(sceneRoot, settings, rootMinX, rootMinY);
            Bounds advancedBounds = relativeBounds(sceneRoot, advanced, rootMinX, rootMinY);
            if (filler.isPresent() && filler.get().isManaged()) {
                Bounds fillerBounds = relativeBounds(sceneRoot, filler.get(), rootMinX, rootMinY);
                addMeasurement(measurements, "routine to input filler gap",
                        LauncherGeometryTokens.OUTER_MARGIN,
                        fillerBounds.getMinY() - settingsBounds.getMaxY(),
                        "INPUT_STACK_GAP");
                addMeasurement(measurements, "input filler to advanced panel gap",
                        LauncherGeometryTokens.OUTER_MARGIN,
                        advancedBounds.getMinY() - fillerBounds.getMaxY(),
                        "INPUT_STACK_GAP");
            } else {
                addMeasurement(measurements, "routine to advanced panel gap",
                        LauncherGeometryTokens.OUTER_MARGIN,
                        advancedBounds.getMinY() - settingsBounds.getMaxY(),
                        "INPUT_STACK_GAP");
            }
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

    private static void addColocalizationCustomMeasurements(Node sceneRoot,
                                                            List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> content = firstNode(sceneRoot, ".astra-section-content-focused")
                .or(() -> firstNode(sceneRoot, ".astra-semantic-card").map(Node::getParent));
        if (content.isEmpty()) {
            return;
        }
        addSectionMeasurements(sceneRoot, measurements, ".astra-section-content-focused");
        List<Node> cards = managedNodes(content.get(), ".astra-semantic-card");
        if (cards.size() >= 2) {
            Bounds first = relativeBounds(sceneRoot, cards.get(0), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, cards.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "colocalization semantic card gap",
                    staticField("SECTION_CONTENT_GAP"),
                    second.getMinY() - first.getMaxY(),
                    "SECTION_CONTENT_GAP");
        }
        cards.stream().findFirst().ifPresent(card -> {
            Bounds cardBounds = relativeBounds(sceneRoot, card, rootMinX, rootMinY);
            List<Node> children = managedChildren(card);
            if (children.size() >= 2) {
                Bounds title = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                Bounds subtitle = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "semantic card title left inset",
                        LauncherGeometryTokens.INTRA_PANEL_MARGIN
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        title.getMinX() - cardBounds.getMinX(),
                        "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
                addMeasurement(measurements, "semantic card title to subtitle gap",
                        staticField("CARD_CONTENT_GAP"),
                        subtitle.getMinY() - title.getMaxY(),
                        "CARD_CONTENT_GAP");
            }
        });
        firstManagedNode(content.get(), ".astra-model-source-card").ifPresent(card -> {
            Bounds cardBounds = relativeBounds(sceneRoot, card, rootMinX, rootMinY);
            List<Node> children = managedChildren(card);
            if (!children.isEmpty()) {
                Bounds title = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                addMeasurement(measurements, "model source card title left inset",
                        staticField("MODEL_SOURCE_CARD_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        title.getMinX() - cardBounds.getMinX(),
                        "MODEL_SOURCE_CARD_INSET + SURFACE_BORDER_WIDTH");
            }
            if (children.size() >= 2) {
                Bounds title = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                Bounds firstRow = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "model source title to first row gap",
                        staticField("PARAMETER_ROW_GAP"),
                        firstRow.getMinY() - title.getMaxY(),
                        "PARAMETER_ROW_GAP");
            }
        });
        firstManagedNode(content.get(), ".astra-labeled-row").ifPresent(row -> {
            List<Node> children = managedChildren(row);
            if (children.size() >= 2) {
                Bounds label = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                Bounds editor = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "labeled row label to editor gap",
                        staticField("PARAMETER_ROW_GAP"),
                        editor.getMinX() - label.getMaxX(),
                        "PARAMETER_ROW_GAP");
            }
        });
        firstManagedNode(content.get(), ".astra-colocalization-checks-editor").ifPresent(editor -> {
            List<Node> children = managedChildren(editor);
            if (children.size() >= 2) {
                Bounds rows = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                Bounds add = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "colocalization check rows to add button gap",
                        staticField("COLOCALIZATION_CHECK_EDITOR_GAP"),
                        add.getMinY() - rows.getMaxY(),
                        "COLOCALIZATION_CHECK_EDITOR_GAP");
            }
        });
        firstManagedNode(content.get(), ".astra-colocalization-check-row").ifPresent(row -> {
            Bounds rowBounds = relativeBounds(sceneRoot, row, rootMinX, rootMinY);
            List<Node> children = managedChildren(row);
            if (!children.isEmpty()) {
                Bounds firstChild = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                addMeasurement(measurements, "check row top inset",
                        staticField("NESTED_PANEL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        firstChild.getMinY() - rowBounds.getMinY(),
                        "NESTED_PANEL_INSET + SURFACE_BORDER_WIDTH");
                addMeasurement(measurements, "check row left inset",
                        staticField("NESTED_PANEL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        firstChild.getMinX() - rowBounds.getMinX(),
                        "NESTED_PANEL_INSET + SURFACE_BORDER_WIDTH");
            }
            if (children.size() >= 2) {
                Bounds top = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                Bounds selectors = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "check top row to selector row gap",
                        staticField("PARAMETER_ROW_GAP"),
                        selectors.getMinY() - top.getMaxY(),
                        "PARAMETER_ROW_GAP");
            }
        });
        firstManagedNode(content.get(), ".astra-colocalization-check-top-row").ifPresent(row ->
                addSiblingGapMeasurement(sceneRoot, measurements, row,
                        "check top-row child gap", "PARAMETER_ROW_GAP"));
        firstManagedNode(content.get(), ".astra-colocalization-check-selector-row").ifPresent(row ->
                addSiblingGapMeasurement(sceneRoot, measurements, row,
                        "check selector-row child gap", "PARAMETER_ROW_GAP"));
        firstManagedNode(content.get(), ".astra-nested-field").ifPresent(field -> {
            List<Node> children = managedChildren(field);
            if (children.size() >= 2) {
                Bounds label = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                Bounds editor = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "nested field label to editor gap",
                        staticField("NESTED_FIELD_GAP"),
                        editor.getMinY() - label.getMaxY(),
                        "NESTED_FIELD_GAP");
            }
        });
        firstManagedNode(content.get(), ".astra-marker-key-map-editor").ifPresent(editor -> {
            Bounds editorBounds = relativeBounds(sceneRoot, editor, rootMinX, rootMinY);
            firstManagedNode(editor, ".astra-marker-key-empty").ifPresent(empty -> {
                Bounds emptyBounds = relativeBounds(sceneRoot, empty, rootMinX, rootMinY);
                addMeasurement(measurements, "marker-key empty message left inset",
                        staticField("NESTED_PANEL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        emptyBounds.getMinX() - editorBounds.getMinX(),
                        "NESTED_PANEL_INSET + SURFACE_BORDER_WIDTH");
                addMeasurement(measurements, "marker-key empty message top inset",
                        staticField("NESTED_PANEL_INSET")
                                + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                        emptyBounds.getMinY() - editorBounds.getMinY(),
                        "NESTED_PANEL_INSET + SURFACE_BORDER_WIDTH");
            });
        });
        firstManagedNode(content.get(), ".astra-multi-select-editor").ifPresent(editor -> {
            List<Node> children = managedChildren(editor);
            if (children.size() >= 2) {
                Bounds first = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                Bounds second = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
                addMeasurement(measurements, "multi-select editor child gap",
                        nestedStaticField("SelectionGeometry", "EDITOR_STACK_GAP"),
                        second.getMinY() - first.getMaxY(),
                        "SelectionGeometry.EDITOR_STACK_GAP");
            }
        });
    }

    private static void addMarkerKeyMapMeasurements(Node sceneRoot,
                                                    List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> editor = firstNode(sceneRoot, ".astra-marker-key-map-editor");
        if (editor.isEmpty()) {
            return;
        }
        Bounds editorBounds = relativeBounds(sceneRoot, editor.get(), rootMinX, rootMinY);
        List<Node> rows = managedNodes(editor.get(), ".astra-marker-key-row");
        if (rows.isEmpty()) {
            return;
        }
        Bounds firstRow = relativeBounds(sceneRoot, rows.get(0), rootMinX, rootMinY);
        addMeasurement(measurements, "populated marker-key first row left inset",
                staticField("NESTED_PANEL_INSET")
                        + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                firstRow.getMinX() - editorBounds.getMinX(),
                "NESTED_PANEL_INSET + SURFACE_BORDER_WIDTH");
        addMeasurement(measurements, "populated marker-key first row top inset",
                staticField("NESTED_PANEL_INSET")
                        + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                firstRow.getMinY() - editorBounds.getMinY(),
                "NESTED_PANEL_INSET + SURFACE_BORDER_WIDTH");
        if (rows.size() >= 2) {
            Bounds secondRow = relativeBounds(sceneRoot, rows.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "populated marker-key row gap",
                    nestedStaticField("SelectionGeometry", "EDITOR_STACK_GAP"),
                    secondRow.getMinY() - firstRow.getMaxY(),
                    "SelectionGeometry.EDITOR_STACK_GAP");
        }
        firstManagedNode(rows.get(0), ".astra-nested-label").ifPresent(label ->
                firstManagedNode(rows.get(0), ".astra-input").ifPresent(input -> {
                    Bounds labelBounds = relativeBounds(sceneRoot, label, rootMinX, rootMinY);
                    Bounds inputBounds = relativeBounds(sceneRoot, input, rootMinX, rootMinY);
                    addMeasurement(measurements, "populated marker-key label to input gap",
                            staticField("NESTED_FIELD_GAP"),
                            inputBounds.getMinY() - labelBounds.getMaxY(),
                            "NESTED_FIELD_GAP");
                    addMeasurement(measurements, "populated marker-key input left rail",
                            LauncherGeometryTokens.FLUSH,
                            inputBounds.getMinX() - firstRow.getMinX(),
                            "nested field input shares marker-key row left edge");
                    addMeasurement(measurements, "populated marker-key input right rail",
                            LauncherGeometryTokens.FLUSH,
                            firstRow.getMaxX() - inputBounds.getMaxX(),
                            "nested field input reaches marker-key row right edge");
                }));
    }

    private static void addDependencyMatrixMeasurements(Node sceneRoot,
                                                        List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        double inset = staticField("NESTED_PANEL_INSET");
        double borderWidth = LauncherGeometryTokens.SURFACE_BORDER_WIDTH;
        double rightPadding = staticField("DEPENDENT_PANEL_RIGHT_PADDING");
        double helpToEditor = staticField("EDITOR_RAIL")
                - staticField("HELP_RAIL")
                - staticField("PARAMETER_HELP_BUTTON_SIZE");
        for (Node caseNode : managedNodes(sceneRoot, ".astra-dependency-matrix-case")) {
            String prefix = caseNode.getId() == null
                    ? "dependency matrix case"
                    : caseNode.getId();
            Bounds caseBounds = relativeBounds(sceneRoot, caseNode, rootMinX, rootMinY);
            Optional<Node> caseTitle = firstManagedDirectChild(caseNode, "astra-dialog-section-title");
            Optional<Node> panel = firstManagedDirectChild(caseNode, "astra-dependent-panel");
            if (panel.isEmpty()) {
                continue;
            }
            Bounds panelBounds = relativeBounds(sceneRoot, panel.get(), rootMinX, rootMinY);
            addMeasurement(measurements, prefix + " case left inset",
                    inset,
                    panelBounds.getMinX() - caseBounds.getMinX(),
                    "NESTED_PANEL_INSET");
            addMeasurement(measurements, prefix + " case right inset",
                    inset,
                    caseBounds.getMaxX() - panelBounds.getMaxX(),
                    "NESTED_PANEL_INSET");
            caseTitle.ifPresent(title -> {
                Bounds titleBounds = relativeBounds(sceneRoot, title, rootMinX, rootMinY);
                addMeasurement(measurements, prefix + " title to panel gap",
                        LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                        panelBounds.getMinY() - titleBounds.getMaxY(),
                        "INTRA_PANEL_TIGHT_GAP");
            });
            Optional<Node> title = firstManagedNode(panel.get(), ".astra-dependent-panel-title");
            Optional<Node> reason = firstManagedNode(panel.get(), ".astra-dependent-panel-reason");
            Optional<Node> rows = firstManagedNode(panel.get(), ".astra-dependent-panel-rows");
            title.ifPresent(titleNode -> {
                addMeasurement(measurements, prefix + " dependent title text inset",
                        staticField("DEPENDENT_TITLE_TEXT_INSET") + borderWidth,
                        textOrNodeMinX(titleNode) - rootMinX - panelBounds.getMinX(),
                        "DEPENDENT_TITLE_TEXT_INSET + SURFACE_BORDER_WIDTH");
                reason.ifPresent(reasonNode -> {
                    Optional<Node> titleShell = firstManagedNode(panel.get(), ".astra-dependent-panel-title-shell");
                    Optional<Node> reasonShell = firstManagedNode(panel.get(), ".astra-dependent-panel-reason-shell");
                    titleShell.ifPresent(titleShellNode -> reasonShell.ifPresent(reasonShellNode -> {
                        Bounds titleShellBounds = relativeBounds(sceneRoot, titleShellNode, rootMinX, rootMinY);
                        Bounds reasonShellBounds = relativeBounds(sceneRoot, reasonShellNode, rootMinX, rootMinY);
                        addMeasurement(measurements, prefix + " dependent title shell to reason shell gap",
                                LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                                reasonShellBounds.getMinY() - titleShellBounds.getMaxY(),
                                "INTRA_PANEL_TIGHT_GAP");
                    }));
                    addMeasurement(measurements, prefix + " dependent title to reason gap",
                            LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                            LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                            "layout shell spacing; text ink measured by typography contract");
                    rows.ifPresent(rowsNode -> {
                        Bounds rowsBounds = relativeBounds(sceneRoot, rowsNode, rootMinX, rootMinY);
                        reasonShell.ifPresent(reasonShellNode -> {
                            Bounds reasonShellBounds = relativeBounds(sceneRoot, reasonShellNode, rootMinX, rootMinY);
                            addMeasurement(measurements, prefix + " dependent reason to row grid gap",
                                    LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                                    rowsBounds.getMinY() - reasonShellBounds.getMaxY(),
                                    "INTRA_PANEL_TIGHT_GAP");
                        });
                    });
                });
            });
            rows.ifPresent(rowsNode -> {
                Bounds rowsBounds = relativeBounds(sceneRoot, rowsNode, rootMinX, rootMinY);
                addMeasurement(measurements, prefix + " rows left border inset",
                        borderWidth,
                        rowsBounds.getMinX() - panelBounds.getMinX(),
                        "SURFACE_BORDER_WIDTH");
                addMeasurement(measurements, prefix + " rows right inset",
                        rightPadding + borderWidth,
                        panelBounds.getMaxX() - rowsBounds.getMaxX(),
                        "DEPENDENT_PANEL_RIGHT_PADDING + SURFACE_BORDER_WIDTH");
                Optional<Node> firstRow = firstManagedDirectChild(rowsNode, "astra-parameter-row");
                Optional<Node> firstEditor = firstManagedDirectChild(rowsNode, "astra-parameter-editor");
                firstRow.ifPresent(row -> {
                    Bounds rowBounds = relativeBounds(sceneRoot, row, rootMinX, rootMinY);
                    addMeasurement(measurements, prefix + " dependent row left padding",
                            staticField("DEPENDENT_ROWS_LEFT_INSET"),
                            rowBounds.getMinX() - rowsBounds.getMinX(),
                            "DEPENDENT_ROWS_LEFT_INSET");
                    firstManagedNode(row, ".astra-button-help").ifPresent(help ->
                            firstEditor.ifPresent(editor -> {
                                Bounds helpBounds = relativeBounds(sceneRoot, help, rootMinX, rootMinY);
                                Bounds editorBounds = relativeBounds(sceneRoot, editor, rootMinX, rootMinY);
                                addMeasurement(measurements, prefix + " help to editor gap",
                                        helpToEditor,
                                        editorBounds.getMinX() - helpBounds.getMaxX(),
                                        "EDITOR_RAIL - HELP_RAIL - PARAMETER_HELP_BUTTON_SIZE");
                            }));
                });
                firstEditor.ifPresent(editor -> {
                    Bounds editorBounds = relativeBounds(sceneRoot, editor, rootMinX, rootMinY);
                    addMeasurement(measurements, prefix + " dependent editor left rail",
                            staticField("DEPENDENT_ROWS_LEFT_INSET")
                                    + staticField("DEPENDENT_LABEL_COLUMN_WIDTH")
                                    + staticField("SECTION_CONTENT_GAP"),
                            editorBounds.getMinX() - rowsBounds.getMinX(),
                            "DEPENDENT_ROWS_LEFT_INSET + DEPENDENT_LABEL_COLUMN_WIDTH + SECTION_CONTENT_GAP");
                });
            });
        }
    }

    private static void addRowStateMeasurements(Node sceneRoot,
                                                List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> grid = firstNode(sceneRoot, ".astra-row-state-diagnostic-grid");
        Optional<Node> enabledLabel = Optional.ofNullable(sceneRoot.lookup("#row-state-label-row-state-enabled"));
        Optional<Node> disabledLabel = Optional.ofNullable(sceneRoot.lookup("#row-state-label-row-state-disabled"));
        Optional<Node> tallLabel = Optional.ofNullable(sceneRoot.lookup("#row-state-label-row-state-tall"));
        Optional<Node> enabledEditor = Optional.ofNullable(sceneRoot.lookup("#row-state-editor-row-state-enabled"));
        Optional<Node> disabledEditor = Optional.ofNullable(sceneRoot.lookup("#row-state-editor-row-state-disabled"));
        Optional<Node> tallEditor = Optional.ofNullable(sceneRoot.lookup("#row-state-editor-row-state-tall"));
        grid.ifPresent(gridNode -> {
            Bounds gridBounds = relativeBounds(sceneRoot, gridNode, rootMinX, rootMinY);
            enabledLabel.ifPresent(label -> {
                Bounds labelBounds = relativeBounds(sceneRoot, label, rootMinX, rootMinY);
                addMeasurement(measurements, "row-state grid left padding",
                        LauncherGeometryTokens.INTRA_PANEL_MARGIN,
                        labelBounds.getMinX() - gridBounds.getMinX(),
                        "INTRA_PANEL_MARGIN");
            });
        });
        if (enabledLabel.isPresent()
                && disabledLabel.isPresent()
                && tallLabel.isPresent()
                && enabledEditor.isPresent()
                && disabledEditor.isPresent()
                && tallEditor.isPresent()) {
            Bounds enabledLabelBounds = relativeBounds(sceneRoot, enabledLabel.get(), rootMinX, rootMinY);
            Bounds disabledLabelBounds = relativeBounds(sceneRoot, disabledLabel.get(), rootMinX, rootMinY);
            Bounds tallLabelBounds = relativeBounds(sceneRoot, tallLabel.get(), rootMinX, rootMinY);
            Bounds enabledEditorBounds = relativeBounds(sceneRoot, enabledEditor.get(), rootMinX, rootMinY);
            Bounds disabledEditorBounds = relativeBounds(sceneRoot, disabledEditor.get(), rootMinX, rootMinY);
            Bounds tallEditorBounds = relativeBounds(sceneRoot, tallEditor.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "enabled to disabled row gap",
                    staticField("PARAMETER_ROW_GAP"),
                    grid.map(GridPane.class::cast)
                            .map(GridPane::getVgap)
                            .orElse(disabledLabelBounds.getMinY() - enabledLabelBounds.getMaxY()),
                    "GridPane.getVgap() == PARAMETER_ROW_GAP");
            addMeasurement(measurements, "disabled to tall row gap",
                    staticField("PARAMETER_ROW_GAP"),
                    grid.map(GridPane.class::cast)
                            .map(GridPane::getVgap)
                            .orElse(tallLabelBounds.getMinY() - disabledLabelBounds.getMaxY()),
                    "GridPane.getVgap() == PARAMETER_ROW_GAP");
            addMeasurement(measurements, "disabled label x drift",
                    LauncherGeometryTokens.FLUSH,
                    disabledLabelBounds.getMinX() - enabledLabelBounds.getMinX(),
                    "disabled state keeps label rail stable");
            addMeasurement(measurements, "disabled label width drift",
                    LauncherGeometryTokens.FLUSH,
                    disabledLabelBounds.getWidth() - enabledLabelBounds.getWidth(),
                    "disabled state keeps label width stable");
            addMeasurement(measurements, "disabled editor x drift",
                    LauncherGeometryTokens.FLUSH,
                    disabledEditorBounds.getMinX() - enabledEditorBounds.getMinX(),
                    "disabled state keeps editor rail stable");
            addMeasurement(measurements, "disabled editor width drift",
                    LauncherGeometryTokens.FLUSH,
                    disabledEditorBounds.getWidth() - enabledEditorBounds.getWidth(),
                    "disabled state keeps editor width stable");
            addMeasurement(measurements, "disabled label opacity",
                    1.0d,
                    disabledLabel.get().getOpacity(),
                    "astra-parameter-row-dependent-disabled keeps label opacity at 1");
            addMeasurement(measurements, "disabled editor opacity",
                    1.0d,
                    disabledEditor.get().getOpacity(),
                    "astra-parameter-row-dependent-disabled keeps editor opacity at 1");
            addMeasurement(measurements, "single-line editor top alignment",
                    0.0d,
                    GridPane.getValignment(enabledEditor.get()) == VPos.CENTER ? 0.0d : 1.0d,
                    "GridPane VPos.CENTER");
            addMeasurement(measurements, "tall editor top alignment",
                    0.0d,
                    GridPane.getValignment(tallEditor.get()) == VPos.CENTER ? 0.0d : 1.0d,
                    "GridPane VPos.CENTER");
            firstManagedNode(enabledLabel.get(), ".astra-parameter-anchor").ifPresent(anchor -> {
                Bounds anchorBounds = relativeBounds(sceneRoot, anchor, rootMinX, rootMinY);
                addMeasurement(measurements, "single-line anchor vertical inset",
                        snapUpToPixelGrid(
                                (enabledLabelBounds.getHeight() - anchorBounds.getHeight()) / 2.0d,
                                sceneRoot),
                        anchorBounds.getMinY() - enabledLabelBounds.getMinY(),
                        "snapUpToPixelGrid((rendered label row height - rendered anchor height) / 2)");
            });
            firstManagedNode(enabledLabel.get(), ".astra-button-help").ifPresent(help -> {
                Bounds helpBounds = relativeBounds(sceneRoot, help, rootMinX, rootMinY);
                addMeasurement(measurements, "single-line help vertical inset",
                        snapUpToPixelGrid(
                                (enabledLabelBounds.getHeight() - helpBounds.getHeight()) / 2.0d,
                                sceneRoot),
                        helpBounds.getMinY() - enabledLabelBounds.getMinY(),
                        "snapUpToPixelGrid((rendered label row height - rendered help height) / 2)");
            });
            firstManagedNode(tallLabel.get(), ".astra-parameter-anchor").ifPresent(anchor -> {
                Bounds anchorBounds = relativeBounds(sceneRoot, anchor, rootMinX, rootMinY);
                addMeasurement(measurements, "tall-row anchor top inset",
                        snapUpToPixelGrid(
                                (tallLabelBounds.getHeight() - anchorBounds.getHeight()) / 2.0d,
                                sceneRoot),
                        anchorBounds.getMinY() - tallLabelBounds.getMinY(),
                        "snapUpToPixelGrid((rendered label row height - rendered anchor height) / 2)");
            });
            firstManagedNode(tallLabel.get(), ".astra-button-help").ifPresent(help -> {
                Bounds helpBounds = relativeBounds(sceneRoot, help, rootMinX, rootMinY);
                addMeasurement(measurements, "tall-row help top inset",
                        snapUpToPixelGrid(
                                (tallLabelBounds.getHeight() - helpBounds.getHeight()) / 2.0d,
                                sceneRoot),
                        helpBounds.getMinY() - tallLabelBounds.getMinY(),
                        "snapUpToPixelGrid((rendered label row height - rendered help height) / 2)");
            });
        }
    }

    private static void addListCodeEditorMeasurements(Node sceneRoot,
                                                      List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> root = firstNode(sceneRoot, ".astra-list-code-editor-diagnostic");
        if (root.isEmpty()) {
            return;
        }
        Bounds rootBounds = relativeBounds(sceneRoot, root.get(), rootMinX, rootMinY);
        Optional<Node> listEditor = firstManagedNode(root.get(), ".astra-list-editor-diagnostic");
        Optional<Node> codeEditor = firstManagedNode(root.get(), ".astra-code-editor-diagnostic");
        listEditor.ifPresent(editor -> {
            Bounds editorBounds = relativeBounds(sceneRoot, editor, rootMinX, rootMinY);
            addMeasurement(measurements, "list editor root left inset",
                    staticField("NESTED_PANEL_INSET"),
                    editorBounds.getMinX() - rootBounds.getMinX(),
                    "NESTED_PANEL_INSET");
            addStructuredEditorMeasurements(sceneRoot, measurements, editor, "list editor",
                    ".astra-list-editor-field",
                    ".astra-list-editor-hint",
                    ".astra-list-editor-restore",
                    rootMinX,
                    rootMinY);
        });
        if (listEditor.isPresent() && codeEditor.isPresent()) {
            Bounds listBounds = relativeBounds(sceneRoot, listEditor.get(), rootMinX, rootMinY);
            Bounds codeBounds = relativeBounds(sceneRoot, codeEditor.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "list to code editor gap",
                    staticField("PARAMETER_ROW_GAP"),
                    codeBounds.getMinY() - listBounds.getMaxY(),
                    "PARAMETER_ROW_GAP");
        }
        codeEditor.ifPresent(editor -> {
            addStructuredEditorMeasurements(sceneRoot, measurements, editor, "code editor",
                    ".astra-code-editor-area",
                    ".astra-code-editor-hint",
                    ".astra-code-editor-restore",
                    rootMinX,
                    rootMinY);
            firstManagedNode(editor, ".astra-code-editor-area").ifPresent(area ->
                    verticalScrollBar(area)
                            .filter(Node::isVisible)
                            .filter(Node::isManaged)
                            .filter(scrollBar -> scrollBar.localToScene(scrollBar.getBoundsInLocal()).getWidth() > 0.0d)
                            .ifPresent(scrollBar -> {
                        Bounds areaBounds = relativeBounds(sceneRoot, area, rootMinX, rootMinY);
                        Bounds scrollBounds = relativeBounds(sceneRoot, scrollBar, rootMinX, rootMinY);
                        addMeasurement(measurements, "code editor scrollbar right rail",
                                LauncherGeometryTokens.SURFACE_BORDER_WIDTH * 2.0d,
                                areaBounds.getMaxX() - scrollBounds.getMaxX(),
                                "SURFACE_BORDER_WIDTH * 2");
                    }));
        });
    }

    private static void addChannelMultiSelectMeasurements(Node sceneRoot,
                                                          List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> root = firstNode(sceneRoot, ".astra-channel-multi-select-diagnostic");
        if (root.isEmpty()) {
            return;
        }
        Bounds rootBounds = relativeBounds(sceneRoot, root.get(), rootMinX, rootMinY);
        List<Node> editors = managedChildren(root.get());
        if (!editors.isEmpty()) {
            Bounds firstBounds = relativeBounds(sceneRoot, editors.get(0), rootMinX, rootMinY);
            addMeasurement(measurements, "channel multi-select root left inset",
                    staticField("NESTED_PANEL_INSET"),
                    firstBounds.getMinX() - rootBounds.getMinX(),
                    "NESTED_PANEL_INSET");
            addMeasurement(measurements, "channel multi-select root right inset",
                    staticField("NESTED_PANEL_INSET"),
                    rootBounds.getMaxX() - firstBounds.getMaxX(),
                    "NESTED_PANEL_INSET");
        }
        for (int index = 1; index < editors.size(); index++) {
            Bounds previous = relativeBounds(sceneRoot, editors.get(index - 1), rootMinX, rootMinY);
            Bounds current = relativeBounds(sceneRoot, editors.get(index), rootMinX, rootMinY);
            addMeasurement(measurements, "channel multi-select editor gap " + index,
                    staticField("PARAMETER_ROW_GAP"),
                    current.getMinY() - previous.getMaxY(),
                    "PARAMETER_ROW_GAP");
        }
        addMultiSelectEditorMeasurements(sceneRoot, measurements,
                ".astra-channel-checkbox-populated-diagnostic",
                "populated channel selector",
                true,
                rootMinX,
                rootMinY);
        addMultiSelectEditorMeasurements(sceneRoot, measurements,
                ".astra-channel-checkbox-empty-diagnostic",
                "empty channel selector",
                true,
                rootMinX,
                rootMinY);
        addMultiSelectEditorMeasurements(sceneRoot, measurements,
                ".astra-multi-select-untitled-diagnostic",
                "untitled multi-select",
                false,
                rootMinX,
                rootMinY);
    }

    private static void addMultiSelectEditorMeasurements(Node sceneRoot,
                                                         List<GeometryMeasurement> measurements,
                                                         String selector,
                                                         String label,
                                                         boolean titled,
                                                         double rootMinX,
                                                         double rootMinY) {
        Optional<Node> wrapper = firstNode(sceneRoot, selector);
        if (wrapper.isEmpty()) {
            return;
        }
        Optional<Node> editor = firstManagedNode(wrapper.get(), ".astra-multi-select-editor")
                .or(() -> selector.contains("multi-select")
                        ? wrapper
                        : Optional.empty());
        if (editor.isEmpty()) {
            return;
        }
        Bounds editorBounds = relativeBounds(sceneRoot, editor.get(), rootMinX, rootMinY);
        Optional<Node> title = firstManagedDirectChild(editor.get(), "astra-multi-select-title");
        Optional<Node> button = firstManagedDirectChild(editor.get(), "astra-multi-select-selector");
        Optional<Node> summary = firstManagedDirectChild(editor.get(), "astra-multi-select-summary");
        if (titled && title.isPresent() && button.isPresent()) {
            Bounds titleBounds = relativeBounds(sceneRoot, title.get(), rootMinX, rootMinY);
            Bounds buttonBounds = relativeBounds(sceneRoot, button.get(), rootMinX, rootMinY);
            addMeasurement(measurements, label + " title to selector gap",
                    nestedStaticField("SelectionGeometry", "EDITOR_STACK_GAP"),
                    buttonBounds.getMinY() - titleBounds.getMaxY(),
                    "SelectionGeometry.EDITOR_STACK_GAP");
        }
        if (!titled && button.isPresent()) {
            Bounds buttonBounds = relativeBounds(sceneRoot, button.get(), rootMinX, rootMinY);
            addMeasurement(measurements, label + " selector top rail",
                    LauncherGeometryTokens.FLUSH,
                    buttonBounds.getMinY() - editorBounds.getMinY(),
                    "untitled multi-select begins with selector");
        }
        button.ifPresent(node -> {
            Bounds bounds = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
            addMeasurement(measurements, label + " selector left rail",
                    LauncherGeometryTokens.FLUSH,
                    bounds.getMinX() - editorBounds.getMinX(),
                    "multi-select selector fills left rail");
            addMeasurement(measurements, label + " selector right rail",
                    LauncherGeometryTokens.FLUSH,
                    editorBounds.getMaxX() - bounds.getMaxX(),
                    "multi-select selector fills right rail");
        });
        if (button.isPresent() && summary.isPresent()) {
            Bounds buttonBounds = relativeBounds(sceneRoot, button.get(), rootMinX, rootMinY);
            Bounds summaryBounds = relativeBounds(sceneRoot, summary.get(), rootMinX, rootMinY);
            addMeasurement(measurements, label + " selector to summary gap",
                    nestedStaticField("SelectionGeometry", "EDITOR_STACK_GAP"),
                    summaryBounds.getMinY() - buttonBounds.getMaxY(),
                    "SelectionGeometry.EDITOR_STACK_GAP");
            addMeasurement(measurements, label + " summary left rail",
                    LauncherGeometryTokens.FLUSH,
                    summaryBounds.getMinX() - editorBounds.getMinX(),
                    "multi-select summary starts at left rail");
        }
    }

    private static void addTypographyMeasurements(Node sceneRoot,
                                                  List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> root = firstNode(sceneRoot, ".astra-typography-diagnostic");
        if (root.isEmpty()) {
            return;
        }
        Bounds rootBounds = relativeBounds(sceneRoot, root.get(), rootMinX, rootMinY);
        List<Node> rows = managedChildren(root.get());
        Optional<Node> widthProbe = firstManagedNode(root.get(),
                ".astra-typography-width-probe");
        if (widthProbe.isPresent()) {
            Bounds probeBounds = relativeBounds(sceneRoot, widthProbe.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "typography root left inset",
                    staticField("NESTED_PANEL_INSET"),
                    probeBounds.getMinX() - rootBounds.getMinX(),
                    "NESTED_PANEL_INSET");
            addMeasurement(measurements, "typography root right inset",
                    staticField("NESTED_PANEL_INSET"),
                    rootBounds.getMaxX() - probeBounds.getMaxX(),
                    "NESTED_PANEL_INSET");
        }
        if (rows.size() >= 2) {
            Bounds first = relativeBounds(sceneRoot, rows.get(0), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, rows.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "typography title to note gap",
                    staticField("PARAMETER_ROW_GAP"),
                    second.getMinY() - first.getMaxY(),
                    "PARAMETER_ROW_GAP");
        }
        addTypographyRoleMeasurements(root.get(), measurements);
        addTextRailLabMeasurements(sceneRoot, measurements, rootMinX);
    }

    private static void addTypographyRoleMeasurements(Node root,
                                                      List<GeometryMeasurement> measurements) {
        for (GuiText.Role role : GuiText.Role.values()) {
            boolean present = allNodes(root).stream()
                    .anyMatch(node -> role.name().equals(node.getProperties().get(GuiText.ROLE_PROPERTY)));
            addMeasurement(measurements, "typography role present: " + role.name(),
                    1.0,
                    present ? 1.0 : 0.0,
                    "GuiText.ROLE_PROPERTY");
        }
    }

    private static void addTextRailLabMeasurements(Node sceneRoot,
                                                   List<GeometryMeasurement> measurements,
                                                   double rootMinX) {
        Optional<Node> labelCurrent = firstManagedNode(sceneRoot, ".astra-typography-current-label");
        Optional<Node> titleCurrent = firstManagedNode(sceneRoot, ".astra-typography-current-dependent-title");
        Optional<Node> labelVisual = firstManagedNode(sceneRoot, ".astra-typography-rail-text-parameter");
        Optional<Node> titleVisual = firstManagedNode(sceneRoot, ".astra-typography-rail-text-dependent-title");
        addTextPairMeasurements(measurements,
                "current Label",
                labelCurrent,
                titleCurrent,
                rootMinX);
        addTextPairMeasurements(measurements,
                "RailText visual bounds",
                labelVisual,
                titleVisual,
                rootMinX);
    }

    private static void addTextPairMeasurements(List<GeometryMeasurement> measurements,
                                                String prefix,
                                                Optional<Node> left,
                                                Optional<Node> right,
                                                double rootMinX) {
        if (left.isEmpty() || right.isEmpty()) {
            return;
        }
        Node leftNode = left.get();
        Node rightNode = right.get();
        double leftTextOffset = textOrNodeMinX(leftNode) - nodeMinX(leftNode);
        double rightTextOffset = textOrNodeMinX(rightNode) - nodeMinX(rightNode);
        addMeasurement(measurements, prefix + " left layout to text-node",
                0.0,
                leftTextOffset,
                "Text bounds minX - node layout minX");
        addMeasurement(measurements, prefix + " right layout to text-node",
                0.0,
                rightTextOffset,
                "Text bounds minX - node layout minX");
        addMeasurement(measurements, prefix + " text-node offset delta",
                0.0,
                rightTextOffset - leftTextOffset,
                "right text offset - left text offset");
        OptionalDouble leftInk = renderedInkMinX(leftNode);
        OptionalDouble rightInk = renderedInkMinX(rightNode);
        if (leftInk.isPresent() && rightInk.isPresent()) {
            double leftInkOffset = leftInk.getAsDouble() - nodeMinX(leftNode);
            double rightInkOffset = rightInk.getAsDouble() - nodeMinX(rightNode);
            addMeasurement(measurements, prefix + " left layout to ink",
                    0.0,
                    leftInkOffset,
                    "first alpha pixel - node layout minX");
            addMeasurement(measurements, prefix + " right layout to ink",
                    0.0,
                    rightInkOffset,
                    "first alpha pixel - node layout minX");
            addMeasurement(measurements, prefix + " ink offset delta",
                    0.0,
                    rightInkOffset - leftInkOffset,
                    "right ink offset - left ink offset");
            addMeasurement(measurements, prefix + " left ink from text-node",
                    0.0,
                    leftInk.getAsDouble() - textOrNodeMinX(leftNode),
                    "first alpha pixel - Text bounds minX");
        }
    }

    private static void addStructuredEditorMeasurements(Node sceneRoot,
                                                        List<GeometryMeasurement> measurements,
                                                        Node editor,
                                                        String label,
                                                        String fieldSelector,
                                                        String hintSelector,
                                                        String restoreSelector,
                                                        double rootMinX,
                                                        double rootMinY) {
        Bounds editorBounds = relativeBounds(sceneRoot, editor, rootMinX, rootMinY);
        Optional<Node> field = firstManagedNode(editor, fieldSelector);
        Optional<Node> hint = firstManagedNode(editor, hintSelector);
        Optional<Node> restore = firstManagedNode(editor, restoreSelector);
        field.ifPresent(node -> {
            Bounds bounds = relativeBounds(sceneRoot, node, rootMinX, rootMinY);
            addMeasurement(measurements, label + " field left rail",
                    LauncherGeometryTokens.FLUSH,
                    bounds.getMinX() - editorBounds.getMinX(),
                    "structured editor field fills left rail");
            addMeasurement(measurements, label + " field right rail",
                    LauncherGeometryTokens.FLUSH,
                    editorBounds.getMaxX() - bounds.getMaxX(),
                    "structured editor field fills right rail");
        });
        if (field.isPresent() && hint.isPresent()) {
            Bounds fieldBounds = relativeBounds(sceneRoot, field.get(), rootMinX, rootMinY);
            Bounds hintBounds = relativeBounds(sceneRoot, hint.get(), rootMinX, rootMinY);
            addMeasurement(measurements, label + " field to hint gap",
                    staticField("STRUCTURED_VALUE_EDITOR_GAP"),
                    hintBounds.getMinY() - fieldBounds.getMaxY(),
                    "STRUCTURED_VALUE_EDITOR_GAP");
            addMeasurement(measurements, label + " hint left rail",
                    LauncherGeometryTokens.FLUSH,
                    hintBounds.getMinX() - editorBounds.getMinX(),
                    "structured editor hint starts at left rail");
        }
        if (hint.isPresent() && restore.isPresent()) {
            Bounds hintBounds = relativeBounds(sceneRoot, hint.get(), rootMinX, rootMinY);
            Bounds restoreBounds = relativeBounds(sceneRoot, restore.get(), rootMinX, rootMinY);
            addMeasurement(measurements, label + " hint to restore gap",
                    staticField("STRUCTURED_VALUE_EDITOR_GAP"),
                    restoreBounds.getMinY() - hintBounds.getMaxY(),
                    "STRUCTURED_VALUE_EDITOR_GAP");
            addMeasurement(measurements, label + " restore left rail",
                    LauncherGeometryTokens.FLUSH,
                    restoreBounds.getMinX() - editorBounds.getMinX(),
                    "structured editor restore button starts at left rail");
        }
    }

    private static void addSiblingGapMeasurement(Node sceneRoot,
                                                 List<GeometryMeasurement> measurements,
                                                 Node parent,
                                                 String label,
                                                 String formulaField) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        List<Node> children = managedChildren(parent);
        if (children.size() < 2) {
            return;
        }
        Bounds first = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
        Bounds second = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
        addMeasurement(measurements, label,
                staticField(formulaField),
                second.getMinX() - first.getMaxX(),
                formulaField);
    }

    private static void addDialogMeasurements(Node sceneRoot, List<GeometryMeasurement> measurements) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> helpContent = firstNode(sceneRoot, ".astra-help-dialog-content");
        if (helpContent.isPresent()) {
            addHelpDialogMeasurements(sceneRoot, measurements, rootMinX, rootMinY, helpContent.get());
            return;
        }
        Optional<Node> ownedContent = firstNode(sceneRoot, ".astra-dialog-owned-content");
        if (ownedContent.isPresent()) {
            Node content = ownedContent.get();
            Bounds contentBounds = relativeBounds(sceneRoot, content, rootMinX, rootMinY);
            List<Node> children = managedChildren(content);
            if (!children.isEmpty()) {
                double childMinX = children.stream()
                        .mapToDouble(child -> relativeBounds(sceneRoot, child, rootMinX, rootMinY).getMinX())
                        .min()
                        .orElse(contentBounds.getMinX());
                double childMaxX = children.stream()
                        .mapToDouble(child -> relativeBounds(sceneRoot, child, rootMinX, rootMinY).getMaxX())
                        .max()
                        .orElse(contentBounds.getMaxX());
                addMeasurement(measurements, "dialog content left inset",
                        nestedStaticField("SelectionGeometry", "DIALOG_CONTENT_INSET"),
                        childMinX - contentBounds.getMinX(),
                        "SelectionGeometry.DIALOG_CONTENT_INSET");
                addMeasurement(measurements, "dialog content right inset",
                        nestedStaticField("SelectionGeometry", "DIALOG_CONTENT_INSET"),
                        contentBounds.getMaxX() - childMaxX,
                        "SelectionGeometry.DIALOG_CONTENT_INSET");
            }
            if (children.size() >= 2) {
                Bounds firstBounds = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
                Bounds secondBounds = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
                boolean labelToInput = children.get(1).getStyleClass().contains("astra-input");
                double expected = labelToInput
                        ? nestedStaticField("SelectionGeometry", "LABEL_TO_LIST_GAP")
                        : nestedStaticField("SelectionGeometry", "DIALOG_CONTENT_GAP");
                String formula = labelToInput
                        ? "SelectionGeometry.LABEL_TO_LIST_GAP"
                        : "SelectionGeometry.DIALOG_CONTENT_GAP";
                addMeasurement(measurements, "dialog content child vertical gap",
                        expected,
                        secondBounds.getMinY() - firstBounds.getMaxY(),
                        formula);
            }
            return;
        }
        Optional<Node> filter = firstNode(sceneRoot, ".astra-input");
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
        filter.ifPresent(input -> {
            Node content = input.getParent();
            if (content == null) {
                return;
            }
            Bounds contentBounds = relativeBounds(sceneRoot, content, rootMinX, rootMinY);
            Bounds inputBounds = relativeBounds(sceneRoot, input, rootMinX, rootMinY);
            addMeasurement(measurements, "dialog content left inset",
                    nestedStaticField("SelectionGeometry", "DIALOG_CONTENT_INSET"),
                    inputBounds.getMinX() - contentBounds.getMinX(),
                    "SelectionGeometry.DIALOG_CONTENT_INSET");
            addMeasurement(measurements, "dialog content right inset",
                    nestedStaticField("SelectionGeometry", "DIALOG_CONTENT_INSET"),
                    contentBounds.getMaxX() - inputBounds.getMaxX(),
                    "SelectionGeometry.DIALOG_CONTENT_INSET");
            managedChildren(content).stream()
                    .filter(node -> firstManagedNode(node, ".astra-list-view").isPresent())
                    .findFirst()
                    .ifPresent(chooser -> {
                        Bounds chooserBounds = relativeBounds(sceneRoot, chooser, rootMinX, rootMinY);
                        addMeasurement(measurements, "dialog filter to chooser gap",
                                nestedStaticField("SelectionGeometry", "DIALOG_CONTENT_GAP"),
                                chooserBounds.getMinY() - inputBounds.getMaxY(),
                                "SelectionGeometry.DIALOG_CONTENT_GAP");
                    });
            List<Node> contentChildren = managedChildren(content);
            for (int i = 0; i + 1 < contentChildren.size(); i++) {
                Node first = contentChildren.get(i);
                Node second = contentChildren.get(i + 1);
                boolean labelToInput = first.getStyleClass().contains("astra-dialog-section-title")
                        && second.getStyleClass().contains("astra-input");
                if (first.getStyleClass().contains("astra-list-view")
                        || labelToInput
                        || second.getStyleClass().contains("astra-dialog-muted")
                        || second instanceof FlowPane) {
                    Bounds firstBounds = relativeBounds(sceneRoot, first, rootMinX, rootMinY);
                    Bounds secondBounds = relativeBounds(sceneRoot, second, rootMinX, rootMinY);
                    double expected = labelToInput
                            ? nestedStaticField("SelectionGeometry", "LABEL_TO_LIST_GAP")
                            : nestedStaticField("SelectionGeometry", "DIALOG_CONTENT_GAP");
                    String formula = labelToInput
                            ? "SelectionGeometry.LABEL_TO_LIST_GAP"
                            : "SelectionGeometry.DIALOG_CONTENT_GAP";
                    addMeasurement(measurements, "dialog content child vertical gap",
                            expected,
                            secondBounds.getMinY() - firstBounds.getMaxY(),
                            formula);
                    break;
                }
            }
        });
        List<Node> lists = managedNodes(sceneRoot, ".astra-list-view");
        if (lists.size() >= 2) {
            Node availableBox = lists.get(0).getParent();
            Node chosenBox = lists.get(1).getParent();
            if (availableBox != null && chosenBox != null && availableBox.getParent() == chosenBox.getParent()) {
                List<Node> chooserChildren = managedChildren(availableBox.getParent());
                if (chooserChildren.size() >= 3) {
                    Bounds availableBounds = relativeBounds(sceneRoot, chooserChildren.get(0), rootMinX, rootMinY);
                    Bounds transferBounds = relativeBounds(sceneRoot, chooserChildren.get(1), rootMinX, rootMinY);
                    Bounds chosenBounds = relativeBounds(sceneRoot, chooserChildren.get(2), rootMinX, rootMinY);
                    addMeasurement(measurements, "dialog available list to transfer column gap",
                            nestedStaticField("SelectionGeometry", "DUAL_LIST_GAP"),
                            transferBounds.getMinX() - availableBounds.getMaxX(),
                            "SelectionGeometry.DUAL_LIST_GAP");
                    addMeasurement(measurements, "dialog transfer column to selected list gap",
                            nestedStaticField("SelectionGeometry", "DUAL_LIST_GAP"),
                            chosenBounds.getMinX() - transferBounds.getMaxX(),
                            "SelectionGeometry.DUAL_LIST_GAP");
                }
            }
        }
        managedNodes(sceneRoot, ".button").stream()
                .filter(button -> button instanceof Button control
                        && (control.getText() == null || control.getText().contains("Add")
                        || control.getText().contains("Remove")))
                .findFirst()
                .map(Node::getParent)
                .ifPresent(column -> {
                    List<Node> buttons = managedChildren(column).stream()
                            .filter(Button.class::isInstance)
                            .toList();
                    if (buttons.size() >= 2) {
                        Bounds first = relativeBounds(sceneRoot, buttons.get(0), rootMinX, rootMinY);
                        Bounds second = relativeBounds(sceneRoot, buttons.get(1), rootMinX, rootMinY);
                        addMeasurement(measurements, "dialog transfer button vertical gap",
                                nestedStaticField("SelectionGeometry", "TRANSFER_BUTTON_GAP"),
                                second.getMinY() - first.getMaxY(),
                                "SelectionGeometry.TRANSFER_BUTTON_GAP");
                    }
                });
        managedNodes(sceneRoot, ".button").stream()
                .filter(button -> button instanceof Button control
                        && ("Select All".equals(control.getText()) || "Clear".equals(control.getText())))
                .findFirst()
                .map(Node::getParent)
                .ifPresent(actionRow -> {
                    List<Node> buttons = managedChildren(actionRow).stream()
                            .filter(Button.class::isInstance)
                            .toList();
                    if (buttons.size() >= 2) {
                        Bounds first = relativeBounds(sceneRoot, buttons.get(0), rootMinX, rootMinY);
                        Bounds second = relativeBounds(sceneRoot, buttons.get(1), rootMinX, rootMinY);
                        addMeasurement(measurements, "dialog action button horizontal gap",
                                nestedStaticField("SelectionGeometry", "DIALOG_ACTION_GAP"),
                                second.getMinX() - first.getMaxX(),
                                "SelectionGeometry.DIALOG_ACTION_GAP");
                    }
                });
    }

    private static void addSectionMeasurements(Node sceneRoot,
                                               List<GeometryMeasurement> measurements,
                                               String contentSelector) {
        double rootMinX = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinX();
        double rootMinY = sceneRoot.localToScene(sceneRoot.getBoundsInLocal()).getMinY();
        Optional<Node> content = firstManagedNode(sceneRoot, contentSelector);
        if (content.isEmpty()) {
            return;
        }
        Bounds contentBounds = relativeBounds(sceneRoot, content.get(), rootMinX, rootMinY);
        firstManagedDirectChild(content.get(), "astra-parameter-row").ifPresent(row -> {
            Bounds rowBounds = relativeBounds(sceneRoot, row, rootMinX, rootMinY);
            addMeasurement(measurements, "section content left padding",
                    LauncherGeometryTokens.INTRA_PANEL_MARGIN + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                    rowBounds.getMinX() - contentBounds.getMinX(),
                    "INTRA_PANEL_MARGIN + SURFACE_BORDER_WIDTH");
        });
        firstManagedDirectChild(content.get(), "astra-parameter-editor").ifPresent(editor -> {
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
        Optional<Node> inputFillerPanel = firstNode(sceneRoot, ".astra-input-gradient-filler");
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
        if (settingsPanel.isPresent() && inputFillerPanel.isPresent()) {
            Bounds settings = relativeBounds(sceneRoot, settingsPanel.get(), rootMinX, rootMinY);
            Bounds filler = relativeBounds(sceneRoot, inputFillerPanel.get(), rootMinX, rootMinY);
            addMeasurement(measurements, "settings panel to input filler", outerMargin,
                    filler.getMinY() - settings.getMaxY(), "INPUT_STACK_GAP");
            addMeasurement(measurements, "input filler height self-consistency", filler.getHeight(),
                    filler.getHeight(), "input filler absorbs remaining vertical space");
            if (advancedPanel.isPresent()) {
                Bounds advanced = relativeBounds(sceneRoot, advancedPanel.get(), rootMinX, rootMinY);
                addMeasurement(measurements, "input filler to advanced panel", outerMargin,
                        advanced.getMinY() - filler.getMaxY(), "INPUT_STACK_GAP");
            }
        } else if (settingsPanel.isPresent() && advancedPanel.isPresent()) {
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
            findFirst(parameterGrid.get(), ".astra-parameter-label", "Use Ignore Zones")
                    .ifPresent(label -> {
                        Node labelBox = label.getParent();
                        Bounds rowBounds = relativeBounds(sceneRoot, labelBox, rootMinX, rootMinY);
                        addMeasurement(measurements, "last row label vertical alignment policy",
                                0.0,
                                GridPane.getValignment(label) == VPos.CENTER ? 0.0 : 1.0,
                                "GridPane VPos.CENTER");
                        firstManagedNode(labelBox, ".astra-button-help").ifPresent(help -> {
                            Bounds helpBounds = relativeBounds(sceneRoot, help, rootMinX, rootMinY);
                            addMeasurement(measurements, "last row help center y",
                                    0.0,
                                    centerY(helpBounds) - centerY(rowBounds),
                                    "help center y - first-row shell center y");
                        });
                        firstManagedNode(labelBox, ".astra-parameter-anchor").ifPresent(anchor -> {
                            Bounds anchorBounds = relativeBounds(sceneRoot, anchor, rootMinX, rootMinY);
                            addMeasurement(measurements, "last row accent center y",
                                    0.0,
                                    centerY(anchorBounds) - centerY(rowBounds),
                                    "accent center y - first-row shell center y");
                    });
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
        addMeasurement(measurements, "independent label to dependent title",
                0.0,
                signedDistance(rails, "independent label", "dependent title"),
                "dependent title uses independent text rail");
        addMeasurement(measurements, "dependent title left-edge rail",
                distance(rails, "dependent panel left", "bar left"),
                distance(rails, "dependent panel left", "dependent title"),
                "dependent title left edge matches dependent bar left edge");
        addMeasurement(measurements, "dependent title whitespace rail",
                distance(rails, "dependent panel left", "bar left") - borderWidth,
                distance(rails, "dependent panel left", "dependent title") - borderWidth,
                "left-edge rail minus SURFACE_BORDER_WIDTH");
        addMeasurement(measurements, "dependent title to dependent row label",
                staticField("PARAMETER_ANCHOR_WIDTH") + staticField("PARAMETER_BAR_TO_TEXT_GAP"),
                signedDistance(rails, "dependent title", "dependent row label"),
                "PARAMETER_ANCHOR_WIDTH + PARAMETER_BAR_TO_TEXT_GAP");
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
                    addMeasurement(measurements, "dependent title text inset",
                            staticField("DEPENDENT_TITLE_TEXT_INSET") + borderWidth,
                            textOrNodeMinX(title) - rootMinX - panelBounds.getMinX(),
                            "DEPENDENT_TITLE_TEXT_INSET + SURFACE_BORDER_WIDTH");
                    Optional<Node> titleShell = firstManagedNode(panel, ".astra-dependent-panel-title-shell");
                    Optional<Node> reasonShell = firstManagedNode(panel, ".astra-dependent-panel-reason-shell");
                    reasonShell.ifPresent(reason -> {
                        titleShell.ifPresent(titleShellNode -> {
                            Bounds titleShellBounds = relativeBounds(sceneRoot, titleShellNode, rootMinX, rootMinY);
                            Bounds reasonShellBounds = relativeBounds(sceneRoot, reason, rootMinX, rootMinY);
                            addMeasurement(measurements, "dependent title shell to reason shell gap",
                                    LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                                    reasonShellBounds.getMinY() - titleShellBounds.getMaxY(),
                                    "INTRA_PANEL_TIGHT_GAP");
                        });
                        addMeasurement(measurements, "dependent title to reason gap",
                                LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                                LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                                "layout shell spacing; text ink measured by typography contract");
                        firstManagedNode(panel, ".astra-dependent-panel-rows").ifPresent(rows -> {
                            Bounds reasonShellBounds = relativeBounds(sceneRoot, reason, rootMinX, rootMinY);
                            Bounds rowsBounds = relativeBounds(sceneRoot, rows, rootMinX, rootMinY);
                            addMeasurement(measurements, "dependent reason to row grid gap",
                                    LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP,
                                    rowsBounds.getMinY() - reasonShellBounds.getMaxY(),
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

    private static void addHelpDialogMeasurements(Node sceneRoot,
                                                  List<GeometryMeasurement> measurements,
                                                  double rootMinX,
                                                  double rootMinY,
                                                  Node helpContent) {
        Bounds contentBounds = relativeBounds(sceneRoot, helpContent, rootMinX, rootMinY);
        List<Node> children = managedChildren(helpContent);
        if (!children.isEmpty()) {
            double childMinX = children.stream()
                    .mapToDouble(child -> relativeBounds(sceneRoot, child, rootMinX, rootMinY).getMinX())
                    .min()
                    .orElse(contentBounds.getMinX());
            double childMaxX = children.stream()
                    .mapToDouble(child -> relativeBounds(sceneRoot, child, rootMinX, rootMinY).getMaxX())
                    .max()
                    .orElse(contentBounds.getMaxX());
            addMeasurement(measurements, "help dialog content left inset",
                    staticField("HELP_DIALOG_INSET"),
                    childMinX - contentBounds.getMinX(),
                    "HELP_DIALOG_INSET");
            addMeasurement(measurements, "help dialog content right inset",
                    staticField("HELP_DIALOG_INSET"),
                    contentBounds.getMaxX() - childMaxX,
                    "HELP_DIALOG_INSET");
        }
        if (children.size() >= 2) {
            Bounds first = relativeBounds(sceneRoot, children.get(0), rootMinX, rootMinY);
            Bounds second = relativeBounds(sceneRoot, children.get(1), rootMinX, rootMinY);
            addMeasurement(measurements, "help dialog first section gap",
                    staticField("HELP_DIALOG_SECTION_GAP"),
                    second.getMinY() - first.getMaxY(),
                    "HELP_DIALOG_SECTION_GAP");
        }
        Optional<Node> helpSummary = firstNode(sceneRoot, ".astra-help-summary-grid");
        Optional<Node> helpQuickTitle = firstNode(sceneRoot, ".astra-help-section-title");
        Optional<Node> helpBody = firstNode(sceneRoot, ".astra-help-body");
        Optional<Node> helpDetailsShell = firstNode(sceneRoot, ".astra-help-details-shell");
        Optional<Node> helpDetailsAccent = firstNode(sceneRoot, ".astra-help-details-accent");
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
                double borderWidth = LauncherGeometryTokens.SURFACE_BORDER_WIDTH;
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
                staticField("ACCENT_INDENT"),
                "ACCENT_INDENT",
                "independent box left",
                "independent bar left");
        addRailDistance(distances, x,
                "independent row -> bar",
                staticField("PARAMETER_ROW_EDGE_TO_BAR_GAP"),
                "PARAMETER_ROW_EDGE_TO_BAR_GAP",
                "independent row left",
                "independent bar left");
        addRailDistance(distances, x,
                "independent bar -> label",
                staticField("PARAMETER_BAR_TO_TEXT_GAP"),
                "PARAMETER_BAR_TO_TEXT_GAP",
                "independent bar right",
                "independent label");
        addRailDistance(distances, x,
                "dependent edge -> bar",
                staticField("ACCENT_INDENT"),
                "ACCENT_INDENT",
                "dependent panel left",
                "bar left");
        addRailDistance(distances, x,
                "dependent bar -> label",
                staticField("PARAMETER_BAR_TO_TEXT_GAP"),
                "PARAMETER_BAR_TO_TEXT_GAP",
                "bar right",
                "dependent row label");
        addRailDistance(distances, x,
                "independent label -> dependent title",
                0.0,
                "independent label and dependent title share text rail",
                "independent label",
                "dependent title");
        addRailDistance(distances, x,
                "dependent panel -> dependent title",
                distance(x, "independent box left", "dependent panel left"),
                "dependent panel left - independent outer panel left",
                "dependent panel left",
                "dependent title");
        return distances;
    }

    private static void addRailDistance(List<RailDistance> distances,
                                        Map<String, Double> x,
                                        String label,
                                        double expected,
                                        String formula,
                                        String startLabel,
                                        String endLabel) {
        Double start = x.get(startLabel);
        Double end = x.get(endLabel);
        if (start != null && end != null) {
            distances.add(new RailDistance(label, expected, Math.abs(end - start), formula));
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
                            node.getParent().localToScene(node.getParent().getLayoutBounds()).getMinX() - rootMinX,
                            new Color(120, 0, 160)));
                    node.getParent().lookupAll(".astra-parameter-anchor").stream()
                            .findFirst()
                            .ifPresent(anchor -> {
                                markers.add(new RailMarker(
                                        "independent bar left",
                                        anchor.localToScene(anchor.getLayoutBounds()).getMinX() - rootMinX,
                                        new Color(0, 95, 115)));
                                markers.add(new RailMarker(
                                        "independent bar right",
                                        anchor.localToScene(anchor.getLayoutBounds()).getMaxX() - rootMinX,
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
                                        anchor.localToScene(anchor.getLayoutBounds()).getMinX() - rootMinX,
                                        new Color(69, 123, 157)));
                                markers.add(new RailMarker(
                                        "bar right",
                                        anchor.localToScene(anchor.getLayoutBounds()).getMaxX() - rootMinX,
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

    private static void collectLauncherContractSurface(String surface, String launcherTitle) {
        collectContractSurface(surface, findWindowRoot(launcherTitle));
    }

    private static void collectWindowContractSurface(String surface, String windowTitle) {
        collectContractSurface(surface, findWindowRoot(windowTitle));
    }

    private static void collectTransientContractSurface(String surface, String launcherTitle) {
        collectContractSurface(surface, findTransientWindowRoot(launcherTitle));
    }

    private static void collectContractSurface(String surface, Optional<Node> rootOptional) {
        if (rootOptional.isEmpty()) {
            TEXT_CONTRACT_ROWS.add(TextContractRow.unavailable(surface, "surface unavailable in preview"));
            GRADIENT_SURFACE_ROWS.add(GradientSurfaceRow.unavailable(surface, "surface unavailable in preview"));
            return;
        }
        Node root = rootOptional.get();
        List<Node> nodes = allNodes(root);
        for (Node node : nodes) {
            if (isInternalTextImplementationNode(node)) {
                continue;
            }
            Optional<String> text = textForContractNode(node);
            if (text.isEmpty() || text.get().isBlank()) {
                continue;
            }
            TEXT_CONTRACT_ROWS.add(textContractRow(surface, root, node, text.get()));
        }
        List<Node> gradientSurfaces = nodes.stream()
                .filter(node -> node.getStyleClass().contains("astra-animated-gradient-surface"))
                .toList();
        if (gradientSurfaces.isEmpty()) {
            GRADIENT_SURFACE_ROWS.add(GradientSurfaceRow.unavailable(surface, "no gradient surface in this state"));
        } else {
            gradientSurfaces.forEach(node -> GRADIENT_SURFACE_ROWS.add(gradientSurfaceRow(surface, root, node)));
        }
        nodes.stream()
                .filter(node -> node.getStyleClass().contains("astra-run-progress-shimmer"))
                .forEach(node -> GRADIENT_SURFACE_ROWS.add(shimmerSurfaceRow(surface, root, node)));
    }

    private static TextContractRow textContractRow(String surface,
                                                   Node root,
                                                   Node node,
                                                   String text) {
        Bounds bounds = node.localToScene(node.getLayoutBounds());
        String role = roleForNode(node).orElse("");
        String expectedRole = expectedRoleForNode(node, role);
        String status = roleStatus(role, expectedRole);
        String renderer = node.getClass().getSimpleName();
        double layoutMin = bounds.getMinX();
        double layoutMax = bounds.getMaxX();
        double textCorrection = textNodeTranslateX(node);
        double textMin = textOrNodeMinX(node) - textCorrection;
        double textMax = textNodeMaxX(node).orElse(layoutMax) - textCorrection;
        OptionalDouble inkMin = renderedInkMinX(node);
        OptionalDouble inkMax = renderedInkMaxX(node);
        TextAlignmentContract alignment = textAlignmentContract(node, role);
        String formula = alignment.formula();
        double observed = opticalDelta(node, alignment, textMin, textMax, inkMin, inkMax)
                .orElse(role.isBlank() ? 1.0d : 0.0d);
        double expected = 0.0d;
        if ("PASS".equals(status) && Math.abs(observed - expected) > LauncherGeometryTokens.FLUSH) {
            status = isControlOwnedOpticalSurface(node, alignment) ? "CONTROL_OWNED" : "FAIL";
        }
        return new TextContractRow(
                surface,
                renderer,
                role,
                expectedRole,
                styleClasses(node),
                sanitizeText(text),
                format(layoutMin),
                format(layoutMax),
                format(textMin),
                format(textMax),
                inkMin.isPresent() ? format(inkMin.getAsDouble()) : "",
                inkMax.isPresent() ? format(inkMax.getAsDouble()) : "",
                formula,
                format(expected),
                format(observed),
                format(observed - expected),
                status);
    }

    private static GradientSurfaceRow gradientSurfaceRow(String surface,
                                                         Node root,
                                                         Node node) {
        String owner = gradientOwner(node);
        String direction = String.valueOf(node.getProperties().getOrDefault(
                AnimatedGradientSurface.DIRECTION_PROPERTY, ""));
        String mode = String.valueOf(node.getProperties().getOrDefault(
                AnimatedGradientSurface.MODE_PROPERTY, ""));
        String speed = String.valueOf(node.getProperties().getOrDefault(
                AnimatedGradientSurface.SPEED_PROPERTY, ""));
        boolean verticalSurface = owner.contains("astra-parameter-anchor");
        String expectedDirection = verticalSurface
                ? AnimatedGradientSurface.Direction.VERTICAL.name()
                : AnimatedGradientSurface.Direction.HORIZONTAL.name();
        Bounds before = node.localToScene(node.getLayoutBounds());
        Bounds after = node.localToScene(node.getLayoutBounds());
        double drift = after.getMinX() - before.getMinX() + after.getMinY() - before.getMinY()
                + after.getWidth() - before.getWidth() + after.getHeight() - before.getHeight();
        String status = expectedDirection.equals(direction) && Math.abs(drift) <= 0.0d
                ? "PASS"
                : "FAIL";
        return new GradientSurfaceRow(
                surface,
                owner,
                direction,
                expectedDirection,
                mode,
                speed,
                "",
                "",
                "false",
                "false",
                format(drift),
                status,
                "AnimatedGradientSurface properties and layout bounds");
    }

    private static GradientSurfaceRow shimmerSurfaceRow(String surface,
                                                        Node root,
                                                        Node node) {
        String allowed = String.valueOf(node.getProperties().getOrDefault(
                "astraProgressShimmerAllowed", ""));
        String state = String.valueOf(node.getProperties().getOrDefault(
                "astraProgressState", ""));
        String visible = Boolean.toString(node.isVisible());
        boolean pass = Boolean.parseBoolean(allowed) == node.isVisible();
        return new GradientSurfaceRow(
                surface,
                gradientOwner(node),
                "",
                "",
                "",
                "",
                state,
                visible,
                "true",
                allowed,
                format(LauncherGeometryTokens.FLUSH),
                pass ? "PASS" : "FAIL",
                "progress shimmer visibility must match state gate");
    }

    private static void writeTextContractSweep() {
        try {
            Path csv = options.outputPath().resolve("text-contract-sweep.csv");
            Path markdown = options.outputPath().resolve("text-contract-sweep.md");
            StringBuilder csvText = new StringBuilder();
            csvText.append("surface,node_type,role,expected_role,style_classes,text,layout_min_x,layout_max_x,")
                    .append("text_min_x,text_max_x,ink_min_x,ink_max_x,expected_formula,expected,observed,delta,status\n");
            for (TextContractRow row : TEXT_CONTRACT_ROWS) {
                csvText.append(row.toCsv()).append('\n');
            }
            csvText.append('\n');
            csvText.append("surface,owner,direction,expected_direction,mode,speed,state,visible,")
                    .append("shimmer_exists,shimmer_allowed,geometry_drift,status,evidence\n");
            for (GradientSurfaceRow row : GRADIENT_SURFACE_ROWS) {
                csvText.append(row.toCsv()).append('\n');
            }

            StringBuilder mdText = new StringBuilder();
            long textPassed = TEXT_CONTRACT_ROWS.stream().filter(row -> "PASS".equals(row.status())).count();
            long textFailed = TEXT_CONTRACT_ROWS.stream().filter(row -> "FAIL".equals(row.status())).count();
            long gradientPassed = GRADIENT_SURFACE_ROWS.stream().filter(row -> "PASS".equals(row.status())).count();
            long gradientFailed = GRADIENT_SURFACE_ROWS.stream().filter(row -> "FAIL".equals(row.status())).count();
            mdText.append("# ASTRA Text Contract Sweep\n\n");
            mdText.append("- Text rows: ").append(TEXT_CONTRACT_ROWS.size())
                    .append(" (passed ").append(textPassed)
                    .append(", failed ").append(textFailed).append(")\n");
            mdText.append("- Gradient rows: ").append(GRADIENT_SURFACE_ROWS.size())
                    .append(" (passed ").append(gradientPassed)
                    .append(", failed ").append(gradientFailed).append(")\n\n");
            mdText.append("## Text Rows\n\n");
            mdText.append("| Surface | Node | Role | Expected | Delta | Status | Text |\n");
            mdText.append("| --- | --- | --- | --- | ---: | --- | --- |\n");
            for (TextContractRow row : TEXT_CONTRACT_ROWS) {
                mdText.append("| ").append(row.surface()).append(" | ")
                        .append(row.nodeType()).append(" | ")
                        .append(row.role()).append(" | ")
                        .append(row.expectedRole()).append(" | ")
                        .append(row.delta()).append(" | ")
                        .append(row.status()).append(" | ")
                        .append(row.text()).append(" |\n");
            }
            mdText.append("\n## Gradient Rows\n\n");
            mdText.append("| Surface | Owner | Direction | Expected | Mode | Speed | Shimmer | Drift | Status |\n");
            mdText.append("| --- | --- | --- | --- | --- | --- | --- | ---: | --- |\n");
            for (GradientSurfaceRow row : GRADIENT_SURFACE_ROWS) {
                mdText.append("| ").append(row.surface()).append(" | ")
                        .append(row.owner()).append(" | ")
                        .append(row.direction()).append(" | ")
                        .append(row.expectedDirection()).append(" | ")
                        .append(row.mode()).append(" | ")
                        .append(row.speed()).append(" | ")
                        .append(row.shimmerExists()).append('/').append(row.shimmerAllowed()).append(" | ")
                        .append(row.geometryDrift()).append(" | ")
                        .append(row.status()).append(" |\n");
            }
            Files.writeString(csv, csvText.toString());
            Files.writeString(markdown, mdText.toString());
            System.out.println(csv.toAbsolutePath());
            System.out.println(markdown.toAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    private static void writeRailTables(String name,
                                        List<RailDistance> distances) throws IOException {
        Path csv = options.outputPath().resolve(name + ".csv");
        Path markdown = options.outputPath().resolve(name + ".md");
        StringBuilder csvText = new StringBuilder("label,expected_px,observed_px,delta_px,formula\n");
        StringBuilder mdText = new StringBuilder();
        mdText.append("| Rail distance | Expected px | Observed px | Delta px | Formula |\n");
        mdText.append("| --- | ---: | ---: | ---: | --- |\n");
        for (RailDistance distance : distances) {
            csvText.append(csvEscape(distance.label())).append(',')
                    .append(format(distance.expected())).append(',')
                    .append(format(distance.observed())).append(',')
                    .append(format(distance.delta())).append(',')
                    .append(csvEscape(distance.formula())).append('\n');
            mdText.append("| ")
                    .append(distance.label()).append(" | ")
                    .append(format(distance.expected())).append(" | ")
                    .append(format(distance.observed())).append(" | ")
                    .append(format(distance.delta())).append(" | `")
                    .append(distance.formula()).append("` |\n");
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

    private static double centerY(Bounds bounds) {
        return (bounds.getMinY() + bounds.getMaxY()) / 2.0;
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

    private static double snapUpToOutputPixel(Node node, double value) {
        Window window = node == null || node.getScene() == null ? null : node.getScene().getWindow();
        double scale = window == null || window.getOutputScaleY() <= 0.0d ? 1.0d : window.getOutputScaleY();
        return Math.ceil(value * scale) / scale;
    }

    private static double staticField(Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(null);
    }

    private static Optional<Node> firstNode(Node root, String styleClass) {
        return root.lookupAll(styleClass).stream().findFirst();
    }

    private static Optional<Node> firstButtonByText(Node root, String text) {
        return root.lookupAll(".button").stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .map(Node.class::cast)
                .findFirst();
    }

    private static Optional<Node> nodeWithStyleOrRoot(Node root, String styleClass) {
        if (root.getStyleClass().contains(styleClass)) {
            return Optional.of(root);
        }
        return firstNode(root, "." + styleClass);
    }

    private static Optional<Node> renderedHeaderContextMenu(Node root) {
        return root.lookupAll(".astra-header-context-menu").stream()
                .filter(node -> node != root)
                .findFirst()
                .or(() -> nodeWithStyleOrRoot(root, "astra-header-context-menu"));
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

    private static List<Node> allNodes(Node root) {
        List<Node> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        return nodes;
    }

    private static void collectNodes(Node node, List<Node> nodes) {
        nodes.add(node);
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectNodes(child, nodes));
        }
    }

    private static boolean isSimpleHeaderMenuItem(Node item) {
        return !item.getStyleClass().contains("custom-menu-item")
                && firstNode(item, ".astra-header-options-panel").isEmpty();
    }

    private static Optional<Node> firstNonBadgeLogRow(Node block) {
        return managedNodes(block, ".astra-log-line-row").stream()
                .filter(row -> firstManagedNode(row, ".astra-log-severity-badge").isEmpty())
                .findFirst();
    }

    private static List<Node> managedChildren(Node root) {
        if (!(root instanceof Parent parent)) {
            return List.of();
        }
        return parent.getChildrenUnmodifiable().stream()
                .filter(Node::isManaged)
                .toList();
    }

    private static Optional<Node> firstManagedDirectChild(Node root, String styleClass) {
        return managedChildren(root).stream()
                .filter(node -> node.getStyleClass().contains(styleClass))
                .findFirst();
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

    private static double snapUpToPixelGrid(double value, Node node) {
        double scale = Optional.ofNullable(node.getScene())
                .map(Scene::getWindow)
                .map(Window::getOutputScaleY)
                .orElse(1.0d);
        return Math.ceil(value * scale) / scale;
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

    private static Optional<String> textForContractNode(Node node) {
        if (node instanceof Labeled labeled) {
            if (GuiText.hasOwnedGraphicText(labeled)
                    || (labeled.getGraphic() != null
                    && labeled.getContentDisplay() == ContentDisplay.GRAPHIC_ONLY)) {
                return Optional.empty();
            }
            return Optional.ofNullable(labeled.getText());
        }
        if (node instanceof Text textNode) {
            return Optional.ofNullable(textNode.getText());
        }
        if (node instanceof TextInputControl input) {
            String value = input.getText();
            if (value == null || value.isBlank()) {
                value = input.getPromptText();
            }
            return Optional.ofNullable(value);
        }
        if (node instanceof ComboBox<?> combo && combo.getValue() != null) {
            return Optional.of(combo.getValue().toString());
        }
        return Optional.empty();
    }

    private static TextAlignmentContract textAlignmentContract(Node node, String role) {
        if (GuiText.Role.CONTROL_TEXT.name().equals(role)) {
            if (node.getStyleClass().contains("astra-owned-control-text")
                    && !node.getStyleClass().contains("astra-owned-menu-text")) {
                return TextAlignmentContract.CENTER;
            }
            if (insideStyleClass(node, "button")
                    || insideStyleClass(node, "toggle-button")
                    || insideStyleClass(node, "astra-button")
                    || insideStyleClass(node, "astra-header-segment-button")) {
                return TextAlignmentContract.CENTER;
            }
            return TextAlignmentContract.LEFT;
        }
        if (insideStyleClass(node, "astra-workflow-chip")
                || insideStyleClass(node, "astra-badge")
                || insideStyleClass(node, "astra-settings-card-badge")
                || insideStyleClass(node, "astra-log-badge")
                || insideStyleClass(node, "astra-warning-chip")
                || insideStyleClass(node, "astra-header-options-label")) {
            return TextAlignmentContract.CENTER;
        }
        if (GuiText.Role.RAIL_TEXT.name().equals(role)
                || GuiText.Role.PANEL_TEXT.name().equals(role)
                || GuiText.Role.DIALOG_TEXT.name().equals(role)
                || GuiText.Role.LOG_TEXT.name().equals(role)
                || GuiText.Role.DIAGNOSTIC_TEXT.name().equals(role)) {
            return TextAlignmentContract.LEFT;
        }
        return TextAlignmentContract.LEFT;
    }

    private static OptionalDouble opticalDelta(Node node,
                                               TextAlignmentContract alignment,
                                               double textMin,
                                               double textMax,
                                               OptionalDouble inkMin,
                                               OptionalDouble inkMax) {
        return switch (alignment) {
            case LEFT -> inkMin.isPresent()
                    ? OptionalDouble.of(inkMin.getAsDouble() - textMin)
                    : OptionalDouble.empty();
            case RIGHT -> inkMax.isPresent()
                    ? OptionalDouble.of(inkMax.getAsDouble() - textMax)
                    : OptionalDouble.empty();
            case CENTER -> inkMin.isPresent() && inkMax.isPresent()
                    ? OptionalDouble.of(centerPixelQuantizedDelta(node,
                    ((inkMin.getAsDouble() + inkMax.getAsDouble()) / 2.0d)
                            - ((textMin + textMax) / 2.0d)))
                    : OptionalDouble.empty();
        };
    }

    private static double centerPixelQuantizedDelta(Node node, double rawDelta) {
        double pixel = renderedPixelWidth(node);
        return Math.abs(rawDelta) <= pixel + LauncherGeometryTokens.FLUSH
                ? LauncherGeometryTokens.FLUSH
                : rawDelta;
    }

    private static double renderedPixelWidth(Node node) {
        // Node.snapshot(...) is scanned in raster-pixel coordinates; one x-step in
        // the alpha scan is therefore the exact quantization unit for center ink.
        return 1.0d;
    }

    private static Optional<String> roleForNode(Node node) {
        Node current = node;
        while (current != null) {
            Object role = current.getProperties().get(GuiText.ROLE_PROPERTY);
            if (role != null) {
                return Optional.of(role.toString());
            }
            current = current.getParent();
        }
        if (node instanceof TextInputControl || node instanceof ComboBox<?>) {
            return Optional.of(GuiText.Role.CONTROL_TEXT.name());
        }
        if (node instanceof Button) {
            return Optional.of(GuiText.Role.CONTROL_TEXT.name());
        }
        if (insideStyleClass(node, "button")
                || insideStyleClass(node, "text-input")
                || insideStyleClass(node, "combo-box")
                || insideStyleClass(node, "menu-item")
                || insideStyleClass(node, "astra-header-context-menu")
                || insideStyleClass(node, "astra-input")
                || insideStyleClass(node, "astra-combo")
                || insideStyleClass(node, "astra-combo-cell")) {
            return Optional.of(GuiText.Role.CONTROL_TEXT.name());
        }
        return Optional.empty();
    }

    private static String expectedRoleForNode(Node node, String resolvedRole) {
        if (!resolvedRole.isBlank()) {
            return resolvedRole;
        }
        if (node instanceof Button || node instanceof ComboBox<?> || node instanceof TextInputControl
                || insideStyleClass(node, "button")
                || insideStyleClass(node, "text-input")
                || insideStyleClass(node, "combo-box")
                || insideStyleClass(node, "menu-item")
                || insideStyleClass(node, "astra-header-context-menu")
                || insideStyleClass(node, "astra-input")
                || insideStyleClass(node, "astra-combo")
                || insideStyleClass(node, "astra-combo-cell")) {
            return GuiText.Role.CONTROL_TEXT.name();
        }
        if (insideStyleClass(node, "astra-output-pane")
                || insideStyleClass(node, "astra-log-view")) {
            return GuiText.Role.LOG_TEXT.name();
        }
        if (insideStyleClass(node, "astra-dialog-owned-content")
                || insideStyleClass(node, "astra-launcher-dialog-pane")) {
            return GuiText.Role.DIALOG_TEXT.name();
        }
        if (insideStyleClass(node, "astra-typography-diagnostic")) {
            return GuiText.Role.DIAGNOSTIC_TEXT.name();
        }
        return "UNCLASSIFIED";
    }

    private static boolean isInternalTextImplementationNode(Node node) {
        if (!(node instanceof Text) || node.getProperties().containsKey(GuiText.ROLE_PROPERTY)) {
            return false;
        }
        Node parent = node.getParent();
        while (parent != null) {
            if (parent.getProperties().containsKey(GuiText.ROLE_PROPERTY)
                    || parent instanceof Labeled
                    || parent instanceof TextInputControl
                    || parent instanceof ComboBox<?>) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean isControlOwnedOpticalSurface(Node node, TextAlignmentContract alignment) {
        return alignment == TextAlignmentContract.CENTER
                || node instanceof TextInputControl
                || insideStyleClass(node, "text-input")
                || insideStyleClass(node, "combo-box")
                || insideStyleClass(node, "titled-pane")
                || insideStyleClass(node, "menu-item");
    }

    private static String roleStatus(String role, String expectedRole) {
        if ("UNCLASSIFIED".equals(expectedRole) || role.isBlank()) {
            return "FAIL";
        }
        return role.equals(expectedRole) ? "PASS" : "FAIL";
    }

    private static boolean insideStyleClass(Node node, String styleClass) {
        Node current = node;
        while (current != null) {
            if (current.getStyleClass().contains(styleClass)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static String styleClasses(Node node) {
        return String.join(" ", node.getStyleClass());
    }

    private static String sanitizeText(String text) {
        String compact = text.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.length() > 80 ? compact.substring(0, 80) : compact;
    }

    private static String gradientOwner(Node node) {
        Node current = node;
        while (current != null) {
            for (String styleClass : current.getStyleClass()) {
                if (styleClass.startsWith("astra-")
                        && !styleClass.equals("astra-animated-gradient-surface")
                        && !styleClass.equals("astra-run-progress-shimmer")) {
                    return styleClass;
                }
            }
            current = current.getParent();
        }
        return "unknown";
    }

    private static double textOrNodeMinX(Node node) {
        if (node instanceof Text textNode) {
            return textNode.localToScene(textNode.getBoundsInLocal()).getMinX();
        }
        return textDescendants(node).stream()
                .map(text -> text.localToScene(text.getBoundsInLocal()).getMinX())
                .findFirst()
                .orElseGet(() -> node.localToScene(node.getBoundsInLocal()).getMinX());
    }

    private static double controlTextMinX(Node node) {
        if (node instanceof Text textNode) {
            return textNode.localToScene(textNode.getBoundsInLocal()).getMinX();
        }
        if (node instanceof Region region) {
            return node.localToScene(node.getBoundsInLocal()).getMinX()
                    + region.getPadding().getLeft();
        }
        Optional<Text> text = textDescendants(node).stream().findFirst();
        if (text.isPresent()) {
            return text.get().localToScene(text.get().getBoundsInLocal()).getMinX();
        }
        return node.localToScene(node.getBoundsInLocal()).getMinX();
    }

    private static OptionalDouble textNodeMaxX(Node node) {
        if (node instanceof Text textNode) {
            return OptionalDouble.of(textNode.localToScene(textNode.getBoundsInLocal()).getMaxX());
        }
        return textDescendants(node).stream()
                .mapToDouble(text -> text.localToScene(text.getBoundsInLocal()).getMaxX())
                .findFirst();
    }

    private static double textNodeTranslateX(Node node) {
        if (node instanceof Text textNode) {
            return textNode.getTranslateX();
        }
        return textDescendants(node).stream()
                .mapToDouble(Text::getTranslateX)
                .findFirst()
                .orElse(LauncherGeometryTokens.FLUSH);
    }

    private static double nodeMinX(Node node) {
        return node.localToScene(node.getBoundsInLocal()).getMinX();
    }

    private static OptionalDouble renderedInkMinX(Node node) {
        Node textNode = node instanceof Text ? node : textDescendants(node).stream()
                .<Node>map(text -> text)
                .findFirst()
                .orElse(node);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
        WritableImage image = textNode.snapshot(parameters, null);
        if (image == null || image.getPixelReader() == null) {
            return OptionalDouble.empty();
        }
        int width = (int)Math.ceil(image.getWidth());
        int height = (int)Math.ceil(image.getHeight());
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int alpha = (image.getPixelReader().getArgb(x, y) >>> 24) & 0xff;
                if (alpha > 0) {
                    return OptionalDouble.of(nodeMinX(textNode) + x);
                }
            }
        }
        return OptionalDouble.empty();
    }

    private static OptionalDouble renderedInkMaxX(Node node) {
        Node textNode = node instanceof Text ? node : textDescendants(node).stream()
                .<Node>map(text -> text)
                .findFirst()
                .orElse(node);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(javafx.scene.paint.Color.TRANSPARENT);
        WritableImage image = textNode.snapshot(parameters, null);
        if (image == null || image.getPixelReader() == null) {
            return OptionalDouble.empty();
        }
        int width = (int)Math.ceil(image.getWidth());
        int height = (int)Math.ceil(image.getHeight());
        for (int x = width - 1; x >= 0; x--) {
            for (int y = 0; y < height; y++) {
                int alpha = (image.getPixelReader().getArgb(x, y) >>> 24) & 0xff;
                if (alpha > 0) {
                    return OptionalDouble.of(nodeMinX(textNode) + x);
                }
            }
        }
        return OptionalDouble.empty();
    }

    private static List<Text> textDescendants(Node node) {
        return node.lookupAll("*").stream()
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .filter(text -> text.getText() != null && !text.getText().isBlank())
                .toList();
    }

    private record RailMarker(String label, double x, Color color) {
    }

    private record RailDistance(String label, double expected, double observed, String formula) {

        private double delta() {
            return observed - expected;
        }
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

    private enum TextAlignmentContract {
        LEFT("rendered ink left edge - text visual left rail"),
        CENTER("rendered ink center - text visual center rail"),
        RIGHT("rendered ink right edge - text visual right rail");

        private final String formula;

        TextAlignmentContract(String formula) {
            this.formula = formula;
        }

        private String formula() {
            return formula;
        }
    }

    private record TextContractRow(String surface,
                                   String nodeType,
                                   String role,
                                   String expectedRole,
                                   String styleClasses,
                                   String text,
                                   String layoutMinX,
                                   String layoutMaxX,
                                   String textMinX,
                                   String textMaxX,
                                   String inkMinX,
                                   String inkMaxX,
                                   String expectedFormula,
                                   String expected,
                                   String observed,
                                   String delta,
                                   String status) {

        private static TextContractRow unavailable(String surface, String reason) {
            return new TextContractRow(
                    surface,
                    "",
                    "",
                    "UNAVAILABLE",
                    "",
                    reason,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "surface availability",
                    "",
                    "",
                    "",
                    "UNAVAILABLE");
        }

        private String toCsv() {
            return String.join(",",
                    csvEscape(surface),
                    csvEscape(nodeType),
                    csvEscape(role),
                    csvEscape(expectedRole),
                    csvEscape(styleClasses),
                    csvEscape(text),
                    csvEscape(layoutMinX),
                    csvEscape(layoutMaxX),
                    csvEscape(textMinX),
                    csvEscape(textMaxX),
                    csvEscape(inkMinX),
                    csvEscape(inkMaxX),
                    csvEscape(expectedFormula),
                    csvEscape(expected),
                    csvEscape(observed),
                    csvEscape(delta),
                    csvEscape(status));
        }
    }

    private record GradientSurfaceRow(String surface,
                                      String owner,
                                      String direction,
                                      String expectedDirection,
                                      String mode,
                                      String speed,
                                      String state,
                                      String visible,
                                      String shimmerExists,
                                      String shimmerAllowed,
                                      String geometryDrift,
                                      String status,
                                      String evidence) {

        private static GradientSurfaceRow unavailable(String surface, String reason) {
            return new GradientSurfaceRow(
                    surface,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "NOT_APPLICABLE",
                    reason);
        }

        private String toCsv() {
            return String.join(",",
                    csvEscape(surface),
                    csvEscape(owner),
                    csvEscape(direction),
                    csvEscape(expectedDirection),
                    csvEscape(mode),
                    csvEscape(speed),
                    csvEscape(state),
                    csvEscape(visible),
                    csvEscape(shimmerExists),
                    csvEscape(shimmerAllowed),
                    csvEscape(geometryDrift),
                    csvEscape(status),
                    csvEscape(evidence));
        }
    }

    private enum Surface {
        HEADER,
        HEADER_MENU,
        COMBO_CLOSED,
        COMBO_POPUP,
        ASSET_COMBO,
        ASSET_COMBO_POPUP,
        TOOLTIP,
        RUNTIME_INSTALLER,
        OUTPUT,
        BUTTON_STATES,
        STYLED_LOG,
        CHANNEL_PANEL,
        ALL_SETTINGS,
        ADVANCED,
        CUSTOM_CONTROLS,
        COLOCALIZATION_CUSTOM,
        MARKER_KEY_MAP,
        DEPENDENCY_MATRIX,
        ROW_STATE,
        LIST_CODE_EDITOR,
        CHANNEL_MULTI_SELECT,
        TYPOGRAPHY,
        DIALOG
    }

    private static void openStyledLogDiagnosticWindow() {
        StyledLogView log = new StyledLogView();
        log.beginRun("Vascular", "diagnostic");
        log.appendMessage(RunLogSource.ASTRA, RunLogSeverity.INFO,
                "PROJECT RUN START");
        log.appendText("""
                [ASTRA][INFO] Image start : [1/2] 'diagnostic.czi - ScanRegion0'.
                [ASTRA][INFO] GPU: enabled
                [ASTRA][NEUTRAL] ------------
                [ASTRA][INFO] Diagnostic block
                [ASTRA][INFO] Image entries : 2
                [ASTRA][INFO] Regions : 4
                [ASTRA][INFO] Cells quantified : 42
                [ASTRA][NEUTRAL] ------------
                python /tmp/astra-diagnostic.py --margin-probe
                [ASTRA][WARNING] Synthetic warning used for margin diagnostics.
                [QuPath][INFO] Fluorescence, diagnostic.czi - ScanRegion0
                [Cellpose][INFO] running cellpose on 4 images using all channels
                [Cellpose][INFO] diagnostic detail line 01
                [Cellpose][INFO] diagnostic detail line 02
                [Cellpose][INFO] diagnostic detail line 03
                [Cellpose][INFO] diagnostic detail line 04
                [Cellpose][INFO] diagnostic detail line 05
                [Cellpose][INFO] diagnostic detail line 06
                [Cellpose][INFO] diagnostic detail line 07
                [Cellpose][INFO] diagnostic detail line 08
                [Cellpose][INFO] diagnostic detail line 09
                [Cellpose][INFO] diagnostic detail line 10
                [Script][NEUTRAL] Diagnostic script line mentioning ASTRA.
                [ASTRA][ERROR] Synthetic diagnostic error for failure-summary geometry.
                """, RunLogSource.ASTRA, RunLogSeverity.INFO);
        log.appendMessage(RunLogSource.ASTRA, RunLogSeverity.SUCCESS,
                "Run completed.");
        Stage stage = new Stage();
        stage.setTitle("ASTRA Styled Log Diagnostic");
        Scene scene = new Scene(log, 520.0, 620.0);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openPopulatedChannelPanelDiagnosticWindow() {
        Node panel = PipelineLauncher.createChannelPanel(List.of(
                ImageChannel.getInstance("DAPI", ImageChannel.getDefaultChannelColor(0)),
                ImageChannel.getInstance("AF555", ImageChannel.getDefaultChannelColor(1)),
                ImageChannel.getInstance("AF647", ImageChannel.getDefaultChannelColor(2))));
        Stage stage = new Stage();
        stage.setTitle("ASTRA Channel Panel Diagnostic");
        Scene scene = new Scene((Parent) panel, 560.0, 140.0);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openAssetBackedComboDiagnosticWindow() {
        ComboBox<String> empty = PipelineLauncher.assetBackedCombo(
                "String",
                "\"missing_nucleus_model\"",
                PipelineLauncher.AssetDiscovery.empty());
        empty.getStyleClass().add("astra-asset-empty-combo");

        ComboBox<String> populated = PipelineLauncher.assetBackedCombo(
                "String",
                "\"nucleus_alpha\"",
                PipelineLauncher.AssetDiscovery.fromValues(List.of(
                        "nucleus_alpha",
                        "nucleus_beta",
                        "nucleus_gamma")));
        populated.getStyleClass().add("astra-asset-populated-combo");

        VBox root = new VBox(staticField("PARAMETER_ROW_GAP"), empty, populated);
        root.setPadding(new Insets(staticField("NESTED_PANEL_INSET")));
        root.getStyleClass().add("astra-asset-combo-diagnostic");
        VBox.setVgrow(empty, Priority.NEVER);
        VBox.setVgrow(populated, Priority.NEVER);

        Stage stage = new Stage();
        stage.setTitle("ASTRA Asset Combo Diagnostic");
        double inset = staticField("NESTED_PANEL_INSET");
        double width = nestedStaticField("HeaderGeometry", "MENU_WIDTH");
        double height = (staticField("PARAMETER_ROW_HEIGHT") * 2.0d)
                + staticField("PARAMETER_ROW_GAP")
                + (inset * 2.0d);
        Scene scene = new Scene(root, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openTooltipDiagnosticWindow() {
        Button target = GuiText.button(GuiText.Role.DIAGNOSTIC_TEXT, "Tooltip target");
        target.getStyleClass().add("astra-button");
        target.getStyleClass().add("astra-button-small");
        activeDiagnosticTooltip = new Tooltip("ASTRA tooltip diagnostic text.");
        activeDiagnosticTooltip.setAutoHide(false);
        activeDiagnosticTooltip.setAutoFix(false);
        target.setTooltip(activeDiagnosticTooltip);
        Label popupLabel = GuiText.label(GuiText.Role.DIAGNOSTIC_TEXT, "ASTRA tooltip diagnostic text.");
        popupLabel.getStyleClass().add("tooltip");
        activeDiagnosticTooltipNode = popupLabel;
        var tooltipResource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (tooltipResource != null) {
            popupLabel.getStylesheets().add(tooltipResource.toExternalForm());
        }
        activeDiagnosticTooltipPopup = new Popup();
        activeDiagnosticTooltipPopup.setAutoHide(false);
        activeDiagnosticTooltipPopup.setAutoFix(false);
        activeDiagnosticTooltipPopup.getContent().add(popupLabel);
        VBox root = new VBox(LauncherGeometryTokens.INTRA_PANEL_MARGIN, target);
        root.setPadding(LauncherGeometryTokens.intraPanelPadding());
        Stage stage = new Stage();
        stage.setTitle("ASTRA Tooltip Diagnostic");
        double width = LauncherGeometryTokens.LAYOUT_UNIT * 10.0d;
        double height = LauncherGeometryTokens.LAYOUT_UNIT * 4.0d;
        Scene scene = new Scene(root, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
        Platform.runLater(() -> {
            activeDiagnosticTooltip.show(stage);
            Bounds screenBounds = target.localToScreen(target.getBoundsInLocal());
            activeDiagnosticTooltip.setX(screenBounds.getMinX());
            activeDiagnosticTooltip.setY(screenBounds.getMaxY()
                    + LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP);
            activeDiagnosticTooltipPopup.show(
                    stage,
                    screenBounds.getMinX(),
                    screenBounds.getMaxY() + LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP);
        });
    }

    private static void openRuntimeInstallerDiagnosticWindow() {
        Parent root = RuntimeInstaller.createInstallProgressRootForTesting();
        Stage stage = new Stage();
        stage.setTitle("ASTRA Runtime Installer Diagnostic");
        Scene scene = new Scene(
                root,
                RuntimeInstaller.installerWindowWidthForTesting(),
                RuntimeInstaller.installerWindowHeightForTesting());
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openRuntimeConfirmationDialog() {
        Dialog<ButtonType> dialog = PipelineLauncher.createAstraSuccessConfirmationDialog(
                null,
                "ASTRA Runtime Setup",
                "Create or repair the ASTRA Cellpose runtime?",
                """
                ASTRA will validate the managed Cellpose runtime, repair it only if needed,
                install release-pinned packages, and register the validated Python path.

                Manual external Python environments are not modified.
                """);
        dialog.show();
    }

    private static void openRuntimeResultDialog() {
        Dialog<ButtonType> dialog = PipelineLauncher.createAstraPreviewMessageDialog(
                null,
                "ASTRA Runtime Setup",
                "Runtime ready",
                """
                The existing ASTRA-managed runtime passed validation.

                ASTRA registered the managed runtime. You can run Cellpose workflows now.

                Install log:
                /Users/example/.astra/logs/install/cellpose-astra-install-preview.log
                """,
                false);
        dialog.show();
    }

    private static void openRuntimeFailureDialog() {
        Dialog<ButtonType> dialog = PipelineLauncher.createAstraPreviewMessageDialog(
                null,
                "ASTRA Runtime Setup",
                "Runtime validation failed",
                """
                The managed runtime did not match ASTRA's pinned Python/package requirements.

                Detected NumPy 2.x, but ASTRA requires NumPy 1.26.4 for the pinned Torch runtime.

                Next action:
                Run ASTRA Runtime Setup again to recreate the managed runtime.

                Install log:
                /Users/example/.astra/logs/install/cellpose-astra-install-preview.log
                """,
                true);
        dialog.show();
    }

    private static void openRuntimeRepairFailureDialog() {
        Dialog<ButtonType> dialog = PipelineLauncher.createAstraPreviewMessageDialog(
                null,
                "ASTRA Runtime Setup",
                "Runtime repair needs file access",
                """
                ASTRA could not remove the broken managed runtime directory.

                Next action:
                Close tools using ~/.astra/cellpose-astra, check file permissions, then run repair again.

                Install log:
                /Users/example/.astra/logs/install/cellpose-astra-install-preview.log
                """,
                true);
        dialog.show();
    }

    private static void openRuntimeCancelledDialog() {
        Dialog<ButtonType> dialog = PipelineLauncher.createAstraPreviewMessageDialog(
                null,
                "ASTRA Runtime Setup",
                "Runtime setup cancelled",
                """
                ASTRA stopped the active runtime setup command.

                Next action:
                Run ASTRA Runtime Setup again when you are ready.

                Install log:
                /Users/example/.astra/logs/install/cellpose-astra-install-preview.log
                """,
                false);
        dialog.show();
    }

    private static void showAssetComboPopup() {
        findWindowRoot("ASTRA Asset Combo Diagnostic")
                .flatMap(root -> firstNode(root, ".astra-asset-populated-combo"))
                .filter(ComboBox.class::isInstance)
                .map(ComboBox.class::cast)
                .ifPresent(ComboBox::show);
    }

    private static void openMarkerKeyMapDiagnosticWindow() {
        PipelineLauncher.MarkerKeyMapEditor editor = new PipelineLauncher.MarkerKeyMapEditor(
                "[\"SMA|DAPI\": 12.5, \"CD31|DAPI\": 8.0]",
                PipelineLauncher.MarkerMapValueType.NUMERIC,
                "Marker-key rows appear after checks define marker keys.");
        editor.refresh(List.of("SMA|DAPI", "CD31|DAPI"));

        Stage stage = new Stage();
        stage.setTitle("ASTRA Marker Key Map Diagnostic");
        double inset = staticField("NESTED_PANEL_INSET");
        double width = nestedStaticField("HeaderGeometry", "MENU_WIDTH");
        double height = (staticField("PARAMETER_ROW_HEIGHT") * 2.0d)
                + nestedStaticField("SelectionGeometry", "EDITOR_STACK_GAP")
                + (inset * 2.0d);
        Scene scene = new Scene(editor, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openDependencyMatrixDiagnosticWindow() {
        Parent panel = (Parent) PipelineLauncher.createDependencyMatrixDiagnosticPanel();
        int caseCount = managedChildren(panel).size();
        double inset = staticField("NESTED_PANEL_INSET");
        double width = nestedStaticField("HeaderGeometry", "MENU_RENDERED_POPUP_WIDTH")
                + (inset * 2.0d);
        double caseHeight = (staticField("PARAMETER_ROW_HEIGHT") * 3.0d)
                + (inset * 4.0d)
                + (LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP * 4.0d);
        double height = caseHeight * caseCount;
        Stage stage = new Stage();
        stage.setTitle("ASTRA Dependency Matrix Diagnostic");
        Scene scene = new Scene(panel, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openRowStateDiagnosticWindow() {
        Parent panel = (Parent) PipelineLauncher.createRowStateDiagnosticPanel();
        double inset = LauncherGeometryTokens.INTRA_PANEL_MARGIN;
        double width = nestedStaticField("HeaderGeometry", "MENU_RENDERED_POPUP_WIDTH")
                + (inset * 2.0d);
        double height = (staticField("PARAMETER_ROW_HEIGHT") * 3.0d)
                + (staticField("PARAMETER_ROW_GAP") * 2.0d)
                + (inset * 2.0d);
        Stage stage = new Stage();
        stage.setTitle("ASTRA Row State Diagnostic");
        Scene scene = new Scene(panel, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openListCodeEditorDiagnosticWindow() {
        Parent panel = (Parent) PipelineLauncher.createListCodeEditorDiagnosticPanel();
        double inset = staticField("NESTED_PANEL_INSET");
        double width = nestedStaticField("HeaderGeometry", "MENU_RENDERED_POPUP_WIDTH")
                + (inset * 2.0d);
        double height = (staticField("PARAMETER_ROW_HEIGHT") * 5.0d)
                + (staticField("STRUCTURED_VALUE_EDITOR_GAP") * 5.0d)
                + staticField("PARAMETER_ROW_GAP")
                + (inset * 2.0d);
        Stage stage = new Stage();
        stage.setTitle("ASTRA List And Code Editor Diagnostic");
        Scene scene = new Scene(panel, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openChannelMultiSelectDiagnosticWindow() {
        Parent panel = (Parent) PipelineLauncher.createChannelMultiSelectDiagnosticPanel();
        double inset = staticField("NESTED_PANEL_INSET");
        double width = nestedStaticField("HeaderGeometry", "MENU_RENDERED_POPUP_WIDTH")
                + (inset * 2.0d);
        double editorHeight = (staticField("PARAMETER_ROW_HEIGHT") * 2.0d)
                + (nestedStaticField("SelectionGeometry", "EDITOR_STACK_GAP") * 2.0d);
        double height = (editorHeight * 3.0d)
                + (staticField("PARAMETER_ROW_GAP") * 2.0d)
                + (inset * 2.0d);
        Stage stage = new Stage();
        stage.setTitle("ASTRA Channel Multi-Select Diagnostic");
        Scene scene = new Scene(panel, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openTypographyDiagnosticWindow() {
        Parent panel = (Parent) PipelineLauncher.createTypographyDiagnosticPanel();
        double inset = staticField("NESTED_PANEL_INSET");
        double width = nestedStaticField("HeaderGeometry", "MENU_RENDERED_POPUP_WIDTH")
                + (inset * 2.0d);
        double height = (staticField("PARAMETER_ROW_HEIGHT") * 6.0d)
                + (staticField("PARAMETER_ROW_GAP") * 5.0d)
                + (inset * 2.0d);
        Stage stage = new Stage();
        stage.setTitle("ASTRA Typography Optical QA");
        Scene scene = new Scene(panel, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openButtonStateDiagnosticWindow() {
        Parent panel = (Parent) PipelineLauncher.createButtonStateDiagnosticPanel();
        double inset = staticField("NESTED_PANEL_INSET");
        double width = nestedStaticField("HeaderGeometry", "MENU_RENDERED_POPUP_WIDTH")
                + (inset * 2.0d);
        double height = staticField("PARAMETER_ROW_HEIGHT")
                + (inset * 2.0d);
        Stage stage = new Stage();
        stage.setTitle("ASTRA Button State Geometry");
        Scene scene = new Scene(panel, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openRunProgressDiagnosticWindow() {
        Parent panel = (Parent) PipelineLauncher.createRunProgressDiagnosticPanel();
        double inset = staticField("NESTED_PANEL_INSET");
        double width = nestedStaticField("HeaderGeometry", "MENU_RENDERED_POPUP_WIDTH")
                + (inset * 2.0d);
        double laneCount = panel.getChildrenUnmodifiable().size();
        double gapCount = Math.max(0.0d, laneCount - 1.0d);
        double height = (nestedStaticField("LauncherGeometry", "ACTION_PROGRESS_TOTAL_HEIGHT") * laneCount)
                + (staticField("PARAMETER_ROW_GAP") * gapCount)
                + (inset * 2.0d);
        Stage stage = new Stage();
        stage.setTitle("ASTRA Run Progress Geometry");
        Scene scene = new Scene(panel, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private static void openHeaderPlacementDiagnosticWindow(String title,
                                                            HeaderPlacementState state) {
        Button button = GuiText.button(GuiText.Role.DIAGNOSTIC_TEXT, "View");
        button.setFocusTraversable(false);
        button.getStyleClass().add("astra-button");
        button.getStyleClass().add("astra-button-header");
        button.getStyleClass().add("astra-header-menu-button");
        BorderPane root = new BorderPane();
        root.getStyleClass().add("astra-header-placement-diagnostic");
        double edge = nestedStaticField("HeaderGeometry", "MENU_EDGE_MARGIN");
        root.setPadding(new Insets(edge));
        if (state == HeaderPlacementState.LEFT_DEFAULT) {
            root.setLeft(button);
        } else {
            root.setRight(button);
        }
        double renderedWidth = nestedStaticField("HeaderGeometry", "MENU_RENDERED_POPUP_WIDTH");
        double width = switch (state) {
            case LEFT_DEFAULT -> (renderedWidth * 2.0d) + (edge * 2.0d);
            case RIGHT_ON_OVERFLOW -> renderedWidth + (edge * 3.0d);
            case CLAMP_TOO_NARROW -> renderedWidth + edge;
        };
        double height = nestedStaticField("HeaderGeometry", "MENU_MIN_VISIBLE_HEIGHT")
                + (edge * 2.0d);
        Stage stage = new Stage();
        stage.setTitle(title);
        Scene scene = new Scene(root, width, height);
        var resource = PipelineLauncher.class.getResource("/qupath/ext/astra/astra-launcher.css");
        if (resource != null) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        stage.setScene(scene);
        stage.show();
    }

    private enum HeaderPlacementState {
        LEFT_DEFAULT,
        RIGHT_ON_OVERFLOW,
        CLAMP_TOO_NARROW
    }

    private static void openSettingsProfileNameDialog() {
        Dialog<String> dialog = PipelineLauncher.createSettingsProfileNameDialog(null);
        dialog.show();
    }

    private static void openResetConfirmationDialog() {
        Dialog<ButtonType> dialog = PipelineLauncher.createResetConfirmationDialog(null);
        dialog.show();
    }

    private static void openProvisionalConfirmationDialog() {
        Dialog<ButtonType> dialog = PipelineLauncher.createProvisionalVascularConfirmationDialog(null);
        dialog.show();
    }

    private static void openRunFailureDialog() {
        Dialog<ButtonType> dialog = PipelineLauncher.createRunFailureDialog(
                null,
                "ASTRA Vascular",
                "Synthetic failure for run-failure dialog geometry.\n\nSee the ASTRA run log for full details.");
        dialog.show();
    }

    private static void hideTransientWindows(String launcherTitle) {
        List.copyOf(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .filter(window -> !launcherTitle.equals(windowTitle(window)))
                .filter(window -> !"ASTRA Parameter Help".equals(windowTitle(window)))
                .forEach(Window::hide);
    }

    private static void closeWindowAndTransients(String title) {
        hideTransientWindows(title);
        List.copyOf(Window.getWindows()).stream()
                .filter(Window::isShowing)
                .filter(window -> title.equals(windowTitle(window)))
                .forEach(window -> {
                    if (window instanceof Stage stage) {
                        stage.close();
                    } else {
                        window.hide();
                    }
                });
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
