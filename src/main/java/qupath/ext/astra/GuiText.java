package qupath.ext.astra;

import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.application.Platform;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;

import java.util.OptionalDouble;

/**
 * Central contract marker for launcher-owned text surfaces.
 */
final class GuiText {

    static final String ROLE_PROPERTY = "astraTextRole";
    static final String OWNED_TEXT_PROPERTY = "astraOwnedText";
    private static final String PENDING_CENTER_CORRECTION_PROPERTY = "astraPendingCenterCorrection";
    private static final String PENDING_LEFT_CORRECTION_PROPERTY = "astraPendingLeftCorrection";
    private static final String EDITABLE_TEXT_ADOPTED_PROPERTY = "astraEditableTextAdopted";
    private static final String EDITABLE_TEXT_PENDING_PROPERTY = "astraEditableTextPending";

    enum Role {
        RAIL_TEXT(
                "Text",
                "-fx-fill",
                "Rendered ink and x-rail diagnostics must remain zero-delta."),
        PANEL_TEXT(
                "Label",
                "-fx-text-fill",
                "Panel text follows panel/card layout rails and local vertical alignment."),
        CONTROL_TEXT(
                "Labeled control or MenuItem",
                "-fx-text-fill",
                "Control text is owned by the interactive control and aligned by control geometry."),
        DIALOG_TEXT(
                "Label",
                "-fx-text-fill",
                "Dialog text follows the shared dialog shell, section, and content rails."),
        LOG_TEXT(
                "Label",
                "-fx-text-fill",
                "Log text follows source/severity card rails and may use dynamic severity classes."),
        DIAGNOSTIC_TEXT(
                "Label, Button, or MenuItem",
                "-fx-text-fill",
                "Diagnostic text is allowed only in preview/measurement surfaces.");

        private final String rendererContract;
        private final String requiredColorProperty;
        private final String alignmentContract;

        Role(String rendererContract, String requiredColorProperty, String alignmentContract) {
            this.rendererContract = rendererContract;
            this.requiredColorProperty = requiredColorProperty;
            this.alignmentContract = alignmentContract;
        }

        String rendererContract() {
            return rendererContract;
        }

        String requiredColorProperty() {
            return requiredColorProperty;
        }

        String alignmentContract() {
            return alignmentContract;
        }
    }

    private GuiText() {
    }

    static Label label(Role role, String text) {
        Label label = new VisualLabel(text);
        mark((javafx.scene.Node)label, role);
        return label;
    }

    static Button button(Role role, String text) {
        Button button = new VisualButton(text);
        mark((javafx.scene.Node)button, role);
        return button;
    }

    static ToggleButton toggleButton(Role role, String text) {
        ToggleButton button = new VisualToggleButton(text);
        mark((javafx.scene.Node)button, role);
        return button;
    }

    static MenuItem menuItem(Role role, String text) {
        Text content = ownedText(role, text);
        content.getStyleClass().add("astra-owned-menu-text");
        CustomMenuItem item = new CustomMenuItem(content);
        item.setText(text == null ? "" : text);
        item.setHideOnClick(true);
        mark(item, role);
        return item;
    }

    static Text railText(String text) {
        Text railText = new Text(text == null ? "" : text);
        railText.setBoundsType(TextBoundsType.VISUAL);
        mark((javafx.scene.Node)railText, Role.RAIL_TEXT);
        return railText;
    }

    static boolean hasOwnedGraphicText(Labeled labeled) {
        if (labeled == null || labeled.getGraphic() == null) {
            return false;
        }
        return containsOwnedText(labeled.getGraphic());
    }

    static void adoptControlText(Labeled labeled) {
        adoptLabeledText(labeled, Role.CONTROL_TEXT);
    }

    static void adoptDialogText(TitledPane titledPane) {
        if (titledPane != null) {
            adoptLabeledText(titledPane, Role.DIALOG_TEXT);
        }
    }

    private static void adoptLabeledText(Labeled labeled, Role role) {
        if (labeled == null) {
            return;
        }
        if (labeled.getGraphic() != null && !hasOwnedGraphicText(labeled)) {
            return;
        }
        Text ownedText = hasOwnedGraphicText(labeled)
                ? firstOwnedText(labeled.getGraphic()).orElseGet(() -> ownedText(role, labeled.getText()))
                : ownedText(role, labeled.getText());
        installOwnedGraphicText(labeled, ownedText);
    }

    static void adoptEditableText(TextInputControl input) {
        if (input == null) {
            return;
        }
        mark((javafx.scene.Node)input, Role.CONTROL_TEXT);
        if (!Boolean.TRUE.equals(input.getProperties().get(EDITABLE_TEXT_ADOPTED_PROPERTY))) {
            input.getProperties().put(EDITABLE_TEXT_ADOPTED_PROPERTY, Boolean.TRUE);
            input.textProperty().addListener((obs, oldValue, newValue) -> scheduleEditableTextCorrection(input));
            input.promptTextProperty().addListener((obs, oldValue, newValue) -> scheduleEditableTextCorrection(input));
            input.skinProperty().addListener((obs, oldValue, newValue) -> scheduleEditableTextCorrection(input));
            input.sceneProperty().addListener((obs, oldValue, newValue) -> scheduleEditableTextCorrection(input));
            input.layoutBoundsProperty().addListener((obs, oldValue, newValue) -> scheduleEditableTextCorrection(input));
        }
        scheduleEditableTextCorrection(input);
    }

    private static void scheduleEditableTextCorrection(TextInputControl input) {
        applyEditableTextCorrection(input);
        if (Boolean.TRUE.equals(input.getProperties().get(EDITABLE_TEXT_PENDING_PROPERTY))) {
            return;
        }
        input.getProperties().put(EDITABLE_TEXT_PENDING_PROPERTY, Boolean.TRUE);
        Platform.runLater(() -> {
            input.getProperties().remove(EDITABLE_TEXT_PENDING_PROPERTY);
            applyEditableTextCorrection(input);
            Platform.runLater(() -> applyEditableTextCorrection(input));
        });
    }

    private static void applyEditableTextCorrection(TextInputControl input) {
        if (input.getScene() == null) {
            return;
        }
        for (javafx.scene.Node node : input.lookupAll("*")) {
            if (node instanceof Text text) {
                text.setBoundsType(TextBoundsType.VISUAL);
                applyLeftRailOpticalCorrection(text, true);
            }
        }
    }

    private static java.util.Optional<Text> firstOwnedText(javafx.scene.Node node) {
        if (node instanceof Text text && Boolean.TRUE.equals(text.getProperties().get(OWNED_TEXT_PROPERTY))) {
            return java.util.Optional.of(text);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                java.util.Optional<Text> match = firstOwnedText(child);
                if (match.isPresent()) {
                    return match;
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean containsOwnedText(javafx.scene.Node node) {
        if (node == null) {
            return false;
        }
        if (Boolean.TRUE.equals(node.getProperties().get(OWNED_TEXT_PROPERTY))) {
            return true;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                if (containsOwnedText(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void mark(javafx.scene.Node node, Role role) {
        node.getProperties().put(ROLE_PROPERTY, role.name());
    }

    static void mark(MenuItem item, Role role) {
        item.getProperties().put(ROLE_PROPERTY, role.name());
    }

    static boolean applyVisualBounds(javafx.scene.Parent parent) {
        boolean changed = false;
        for (Text text : textDescendants(parent)) {
            if (text.getBoundsType() != TextBoundsType.VISUAL) {
                text.setBoundsType(TextBoundsType.VISUAL);
                changed = true;
            }
        }
        return changed;
    }

    private static void applyLeftRailOpticalCorrection(javafx.scene.Parent parent) {
        if (!usesLeftRailCorrection(parent)) {
            return;
        }
        for (Text text : textDescendants(parent)) {
            applyLeftRailOpticalCorrection(text, true);
        }
    }

    private static boolean usesLeftRailCorrection(javafx.scene.Parent parent) {
        Object role = parent.getProperties().get(ROLE_PROPERTY);
        if (Role.PANEL_TEXT.name().equals(role)
                || Role.CONTROL_TEXT.name().equals(role)
                || Role.DIALOG_TEXT.name().equals(role)
                || Role.LOG_TEXT.name().equals(role)
                || Role.DIAGNOSTIC_TEXT.name().equals(role)) {
            return !isCenteredTextSurface(parent);
        }
        return false;
    }

    private static boolean isCenteredTextSurface(javafx.scene.Parent parent) {
        return parent.getStyleClass().contains("astra-workflow-chip")
                || parent.getStyleClass().contains("astra-badge")
                || parent.getStyleClass().contains("astra-settings-card-badge")
                || parent.getStyleClass().contains("astra-log-badge")
                || parent.getStyleClass().contains("astra-warning-chip");
    }

    private static void applyLeftRailOpticalCorrection(Text text) {
        applyLeftRailOpticalCorrection(text, false);
    }

    private static void applyLeftRailOpticalCorrection(Text text, boolean force) {
        if (text.getScene() == null || text.getText() == null || text.getText().isBlank()) {
            return;
        }
        if (!force && !usesLeftRailCorrection(text)) {
            return;
        }
        OptionalDouble inkMin = renderedInkMinX(text);
        if (inkMin.isEmpty()) {
            return;
        }
        double correction = text.getTranslateX();
        double uncorrectedRail = text.localToScene(text.getBoundsInLocal()).getMinX() - correction;
        text.setTranslateX(correction - (inkMin.getAsDouble() - uncorrectedRail));
    }

    private static void applyLeftRailOpticalCorrectionAfterPulse(Text text) {
        if (Boolean.TRUE.equals(text.getProperties().get(PENDING_LEFT_CORRECTION_PROPERTY))) {
            return;
        }
        text.getProperties().put(PENDING_LEFT_CORRECTION_PROPERTY, Boolean.TRUE);
        Platform.runLater(() -> {
            text.getProperties().remove(PENDING_LEFT_CORRECTION_PROPERTY);
            applyLeftRailOpticalCorrection(text);
        });
    }

    private static boolean usesLeftRailCorrection(Text text) {
        Object role = text.getProperties().get(ROLE_PROPERTY);
        if (Role.CONTROL_TEXT.name().equals(role)) {
            return text.getStyleClass().contains("astra-owned-menu-text");
        }
        return Role.PANEL_TEXT.name().equals(role)
                || Role.DIALOG_TEXT.name().equals(role)
                || Role.LOG_TEXT.name().equals(role)
                || Role.DIAGNOSTIC_TEXT.name().equals(role);
    }

    private static void applyCenterOpticalCorrection(Text text) {
        if (text.getScene() == null || text.getText() == null || text.getText().isBlank()) {
            return;
        }
        OptionalDouble inkMin = renderedInkMinX(text);
        OptionalDouble inkMax = renderedInkMaxX(text);
        if (inkMin.isEmpty() || inkMax.isEmpty()) {
            return;
        }
        double correction = text.getTranslateX();
        javafx.geometry.Bounds bounds = text.localToScene(text.getBoundsInLocal());
        double uncorrectedCenter = ((bounds.getMinX() + bounds.getMaxX()) / 2.0d) - correction;
        double inkCenter = (inkMin.getAsDouble() + inkMax.getAsDouble()) / 2.0d;
        text.setTranslateX(correction - (inkCenter - uncorrectedCenter));
    }

    private static void applyCenterOpticalCorrectionAfterPulse(Text text) {
        if (Boolean.TRUE.equals(text.getProperties().get(PENDING_CENTER_CORRECTION_PROPERTY))) {
            return;
        }
        text.getProperties().put(PENDING_CENTER_CORRECTION_PROPERTY, Boolean.TRUE);
        Platform.runLater(() -> {
            text.getProperties().remove(PENDING_CENTER_CORRECTION_PROPERTY);
            applyCenterOpticalCorrection(text);
        });
    }

    private static OptionalDouble renderedInkMinX(Text text) {
        WritableImage image = textSnapshot(text);
        if (image == null || image.getPixelReader() == null) {
            return OptionalDouble.empty();
        }
        int width = (int)Math.ceil(image.getWidth());
        int height = (int)Math.ceil(image.getHeight());
        double sceneMinX = text.localToScene(text.getBoundsInLocal()).getMinX();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int alpha = (image.getPixelReader().getArgb(x, y) >>> 24) & 0xff;
                if (alpha > 0) {
                    return OptionalDouble.of(sceneMinX + x);
                }
            }
        }
        return OptionalDouble.empty();
    }

    private static OptionalDouble renderedInkMaxX(Text text) {
        WritableImage image = textSnapshot(text);
        if (image == null || image.getPixelReader() == null) {
            return OptionalDouble.empty();
        }
        int width = (int)Math.ceil(image.getWidth());
        int height = (int)Math.ceil(image.getHeight());
        double sceneMinX = text.localToScene(text.getBoundsInLocal()).getMinX();
        for (int x = width - 1; x >= 0; x--) {
            for (int y = 0; y < height; y++) {
                int alpha = (image.getPixelReader().getArgb(x, y) >>> 24) & 0xff;
                if (alpha > 0) {
                    return OptionalDouble.of(sceneMinX + x);
                }
            }
        }
        return OptionalDouble.empty();
    }

    private static Text ownedControlText(String text) {
        return ownedText(Role.CONTROL_TEXT, text);
    }

    private static Text ownedText(Role role, String text) {
        Text ownedText = new Text(text == null ? "" : text);
        ownedText.setBoundsType(TextBoundsType.VISUAL);
        ownedText.getProperties().put(OWNED_TEXT_PROPERTY, Boolean.TRUE);
        ownedText.getStyleClass().add(switch (role) {
            case PANEL_TEXT -> "astra-owned-panel-text";
            case DIALOG_TEXT -> "astra-owned-dialog-text";
            case LOG_TEXT -> "astra-owned-log-text";
            default -> "astra-owned-control-text";
        });
        mark((javafx.scene.Node)ownedText, role);
        ownedText.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                applyLeftRailOpticalCorrection(ownedText);
                applyLeftRailOpticalCorrectionAfterPulse(ownedText);
            }
        });
        ownedText.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
            applyLeftRailOpticalCorrection(ownedText);
            applyLeftRailOpticalCorrectionAfterPulse(ownedText);
        });
        return ownedText;
    }

    private static Text ownedPanelText(String text) {
        return ownedText(Role.PANEL_TEXT, text);
    }

    private static WritableImage textSnapshot(Text text) {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return text.snapshot(parameters, null);
    }

    private static java.util.List<Text> textDescendants(javafx.scene.Parent parent) {
        return parent.lookupAll("*").stream()
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .filter(text -> text.getText() != null && !text.getText().isBlank())
                .toList();
    }

    private static final class VisualLabel extends Label {
        private Text ownedCenteredText;

        private VisualLabel(String text) {
            super(text == null ? "" : text);
            sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) {
                    applyLeftRailOpticalCorrection(this);
                    if (ownedCenteredText != null) {
                        applyCenterOpticalCorrection(ownedCenteredText);
                        applyCenterOpticalCorrectionAfterPulse(ownedCenteredText);
                    }
                }
            });
            layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> {
                applyLeftRailOpticalCorrection(this);
                if (ownedCenteredText != null) {
                    applyCenterOpticalCorrection(ownedCenteredText);
                    applyCenterOpticalCorrectionAfterPulse(ownedCenteredText);
                }
            });
        }

        @Override
        protected void layoutChildren() {
            installOwnedCenteredGraphicIfNeeded();
            super.layoutChildren();
            if (applyVisualBounds(this)) {
                super.layoutChildren();
            }
            applyLeftRailOpticalCorrection(this);
            if (ownedCenteredText != null) {
                applyCenterOpticalCorrection(ownedCenteredText);
                applyCenterOpticalCorrectionAfterPulse(ownedCenteredText);
            }
        }

        private void installOwnedCenteredGraphicIfNeeded() {
            if (!isCenteredTextSurface(this)) {
                return;
            }
            if (getGraphic() != null && getGraphic() != ownedCenteredText) {
                return;
            }
            if (ownedCenteredText == null) {
                ownedCenteredText = ownedPanelText(getText());
            }
            installOwnedGraphicText(this, ownedCenteredText);
        }
    }

    private static final class VisualButton extends Button {
        private final Text ownedText;

        private VisualButton(String text) {
            super(text == null ? "" : text);
            ownedText = ownedControlText(text);
            installOwnedGraphicText(this, ownedText);
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            if (applyVisualBounds(this)) {
                super.layoutChildren();
            }
            applyCenterOpticalCorrection(ownedText);
            applyCenterOpticalCorrectionAfterPulse(ownedText);
        }
    }

    private static final class VisualToggleButton extends ToggleButton {
        private final Text ownedText;

        private VisualToggleButton(String text) {
            super(text == null ? "" : text);
            ownedText = ownedControlText(text);
            installOwnedGraphicText(this, ownedText);
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            if (applyVisualBounds(this)) {
                super.layoutChildren();
            }
            applyCenterOpticalCorrection(ownedText);
            applyCenterOpticalCorrectionAfterPulse(ownedText);
        }
    }

    private static void installOwnedGraphicText(Labeled labeled, Text ownedText) {
        if (labeled.getGraphic() != null && labeled.getGraphic() != ownedText) {
            return;
        }
        if (labeled.getGraphic() == null) {
            labeled.setGraphic(ownedText);
        }
        labeled.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }
}
