package qupath.ext.astra;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.languages.GroovyLanguage;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.scripting.ScriptParameters;

import javax.script.ScriptException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

/**
 * Constrained linear workflow composer for manifest-declared tool modules.
 */
final class WorkflowBuilder {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowBuilder.class);
    private static final String GUI_RUN_ACTIVE_PROPERTY = "ASTRA_GUI_RUN_ACTIVE";
    private static final String STYLESHEET_RESOURCE = "/qupath/ext/astra/launcher.css";
    private static final String CATALOG_DRAG_PREFIX = "catalog:";
    private static final String LANE_DRAG_PREFIX = "lane:";

    private static Stage activeStage;

    private WorkflowBuilder() {
    }

    static void show(QuPathGUI qupath) {
        open(qupath, 0, -1, ManifestSet.load());
    }

    static void showPreview(QuPathGUI qupath, ManifestSet manifests) {
        open(qupath, 1, -1, manifests);
    }

    static void showFailurePreview(QuPathGUI qupath, ManifestSet manifests) {
        open(qupath, 3, 1, manifests);
    }

    private static void open(
            QuPathGUI qupath,
            int previewStepCount,
            int previewFailureIndex,
            ManifestSet manifests) {
        Objects.requireNonNull(qupath, "qupath");
        Objects.requireNonNull(manifests, "manifests");
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> open(
                    qupath,
                    previewStepCount,
                    previewFailureIndex,
                    manifests));
            return;
        }
        if (activeStage != null && activeStage.isShowing()) {
            activeStage.toFront();
            activeStage.requestFocus();
            return;
        }

        BuilderView view = new BuilderView(qupath, WorkflowBlockCatalog.load(manifests));
        int seedCount = Math.min(previewStepCount, view.catalog().size());
        for (int index = 0; index < seedCount; index++) {
            view.addBlock(view.catalog().get(index), index);
        }
        if (previewFailureIndex >= 0 && previewFailureIndex < seedCount) {
            view.showFailurePreview(previewFailureIndex);
        }
        Stage stage = new Stage();
        activeStage = stage;
        stage.setTitle("Workflow Builder");
        Window owner = qupath.getStage();
        if (owner != null && owner.isShowing()) {
            stage.initOwner(owner);
        }
        Scene scene = new Scene(view.root(), Geometry.WINDOW_WIDTH, Geometry.WINDOW_HEIGHT);
        installStyles(scene, view.root());
        stage.setScene(scene);
        stage.setMinWidth(Geometry.WINDOW_MIN_WIDTH);
        stage.setMinHeight(Geometry.WINDOW_MIN_HEIGHT);
        stage.setOnCloseRequest(event -> {
            if (view.running()) {
                event.consume();
                view.showStatus(
                        "Cancel the active workflow before closing this window.",
                        BuilderView.Status.WARNING);
            }
        });
        stage.setOnHidden(event -> {
            view.dispose();
            if (activeStage == stage) {
                activeStage = null;
            }
        });
        stage.show();
    }

    private static void installStyles(Scene scene, Node root) {
        var resource = WorkflowBuilder.class.getResource(STYLESHEET_RESOURCE);
        if (resource != null && !scene.getStylesheets().contains(resource.toExternalForm())) {
            scene.getStylesheets().add(resource.toExternalForm());
        }
        PipelineLauncher.applyCurrentVisualTheme(root);
    }

    private static final class BuilderView {

        private final QuPathGUI qupath;
        private final List<WorkflowBlockCatalog.WorkflowBlock> catalog;
        private final List<WorkflowBlockCatalog.WorkflowBlock> workflow = new ArrayList<>();
        private final NotebookRunState notebook = new NotebookRunState();
        private final BorderPane root = new BorderPane();
        private final VBox catalogList = new VBox(Geometry.COMPACT_GAP);
        private final VBox lane = new VBox(Geometry.COMPACT_GAP);
        private final Label validationLabel = GuiText.label(GuiText.Role.PANEL_TEXT, "");
        private final Label statusLabel = GuiText.label(GuiText.Role.PANEL_TEXT, "Ready.");
        private final ProgressBar progress = new ProgressBar(0.0);
        private final TextArea technicalLog = new TextArea();
        private final Button runButton = controlButton("Run Workflow", "astra-button-primary");
        private final Button cancelButton = controlButton("Cancel", "astra-button-secondary");
        private final BooleanProperty running = new SimpleBooleanProperty(false);
        private volatile Future<?> runningFuture;

        private BuilderView(QuPathGUI qupath, List<WorkflowBlockCatalog.WorkflowBlock> catalog) {
            this.qupath = Objects.requireNonNull(qupath, "qupath");
            this.catalog = List.copyOf(catalog);
            build();
        }

        private BorderPane root() {
            return root;
        }

        private List<WorkflowBlockCatalog.WorkflowBlock> catalog() {
            return catalog;
        }

        private boolean running() {
            return running.get();
        }

        private void build() {
            root.getStyleClass().addAll("astra-launcher-root", "astra-workflow-builder");
            root.setTop(createHeader());
            root.setCenter(createWorkspace());
            root.setBottom(createFooter());
            BorderPane.setMargin(root.getCenter(), Geometry.OUTER_INSETS);
            BorderPane.setMargin(root.getBottom(), Geometry.FOOTER_INSETS);
            refreshCatalog("");
            rebuildLane();
            running.addListener((observable, oldValue, newValue) -> updateRunningState(newValue));
        }

        private Node createHeader() {
            VBox copy = new VBox(Geometry.COMPACT_GAP);
            copy.setPadding(Geometry.OUTER_INSETS);
            Label title = GuiText.label(GuiText.Role.PANEL_TEXT, "Workflow Builder");
            title.getStyleClass().add("astra-workflow-builder-title");
            Label subtitle = GuiText.label(
                    GuiText.Role.PANEL_TEXT,
                    "Assemble focused tools into a validated linear workflow.");
            subtitle.getStyleClass().add("astra-workflow-builder-subtitle");
            copy.getChildren().addAll(title, subtitle);
            AnimatedGradientHeader header = new AnimatedGradientHeader(copy);
            header.setMinHeight(Geometry.HEADER_HEIGHT);
            header.setPrefHeight(Geometry.HEADER_HEIGHT);
            return header;
        }

        private Node createWorkspace() {
            HBox workspace = new HBox(Geometry.PANEL_GAP);
            VBox catalogPanel = createCatalogPanel();
            VBox lanePanel = createLanePanel();
            workspace.getChildren().addAll(catalogPanel, lanePanel);
            HBox.setHgrow(lanePanel, Priority.ALWAYS);
            return workspace;
        }

        private VBox createCatalogPanel() {
            VBox panel = new VBox(Geometry.INNER_GAP);
            panel.getStyleClass().addAll("astra-panel", "astra-workflow-catalog");
            panel.setPadding(Geometry.PANEL_INSETS);
            panel.setMinWidth(Geometry.CATALOG_WIDTH);
            panel.setPrefWidth(Geometry.CATALOG_WIDTH);
            panel.setMaxWidth(Geometry.CATALOG_WIDTH);

            Label title = GuiText.label(GuiText.Role.PANEL_TEXT, "Tool Library");
            title.getStyleClass().add("astra-workflow-panel-title");
            Label help = GuiText.label(
                    GuiText.Role.PANEL_TEXT,
                    "Search, then drag a tool into the workflow.");
            help.getStyleClass().add("astra-workflow-panel-description");
            help.setWrapText(true);

            TextField search = new TextField();
            search.setPromptText("Search tools");
            search.getStyleClass().add("astra-input");
            search.setMinHeight(Geometry.CONTROL_HEIGHT);
            search.setPrefHeight(Geometry.CONTROL_HEIGHT);
            search.textProperty().addListener((observable, oldValue, newValue) -> refreshCatalog(newValue));

            ScrollPane scroll = new ScrollPane(catalogList);
            scroll.getStyleClass().add("astra-workflow-scroll");
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            panel.getChildren().addAll(title, help, search, scroll);
            return panel;
        }

        private VBox createLanePanel() {
            VBox panel = new VBox(Geometry.INNER_GAP);
            panel.getStyleClass().addAll("astra-panel", "astra-workflow-lane-panel");
            panel.setPadding(Geometry.PANEL_INSETS);

            HBox heading = new HBox(Geometry.INNER_GAP);
            heading.setAlignment(Pos.CENTER_LEFT);
            Label title = GuiText.label(GuiText.Role.PANEL_TEXT, "Workflow");
            title.getStyleClass().add("astra-workflow-panel-title");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label linear = GuiText.label(GuiText.Role.PANEL_TEXT, "Linear");
            linear.getStyleClass().add("astra-workflow-mode-badge");
            heading.getChildren().addAll(title, spacer, linear);

            Label help = GuiText.label(
                    GuiText.Role.PANEL_TEXT,
                    "Tools run from top to bottom. Drag cards to insert or reorder them.");
            help.getStyleClass().add("astra-workflow-panel-description");
            help.setWrapText(true);

            ScrollPane scroll = new ScrollPane(lane);
            scroll.getStyleClass().add("astra-workflow-scroll");
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            VBox.setVgrow(scroll, Priority.ALWAYS);

            TitledPane logPane = new TitledPane("Technical Log", technicalLog);
            logPane.getStyleClass().add("astra-workflow-log-disclosure");
            logPane.setExpanded(false);
            technicalLog.setEditable(false);
            technicalLog.setWrapText(false);
            technicalLog.getStyleClass().add("astra-workflow-log");
            technicalLog.setPrefRowCount(Geometry.LOG_ROW_COUNT);

            panel.getChildren().addAll(heading, help, scroll, logPane);
            return panel;
        }

        private Node createFooter() {
            VBox footer = new VBox(Geometry.COMPACT_GAP);
            HBox actions = new HBox(Geometry.INNER_GAP);
            actions.setAlignment(Pos.CENTER_RIGHT);
            validationLabel.getStyleClass().add("astra-workflow-validation");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            progress.setPrefWidth(Geometry.PROGRESS_WIDTH);
            progress.setMaxWidth(Geometry.PROGRESS_WIDTH);
            progress.getStyleClass().add("astra-workflow-progress");
            progress.setVisible(false);
            progress.setManaged(false);
            configureMacroButton(cancelButton);
            configureMacroButton(runButton);
            cancelButton.setOnAction(event -> cancel());
            runButton.setOnAction(event -> run());
            actions.getChildren().addAll(validationLabel, spacer, progress, cancelButton, runButton);
            statusLabel.getStyleClass().add("astra-workflow-status");
            footer.getChildren().addAll(statusLabel, actions);
            return footer;
        }

        private void refreshCatalog(String query) {
            catalogList.getChildren().clear();
            List<WorkflowBlockCatalog.WorkflowBlock> matches = WorkflowBlockCatalog.search(catalog, query);
            if (matches.isEmpty()) {
                Label empty = GuiText.label(GuiText.Role.PANEL_TEXT, "No matching tools.");
                empty.getStyleClass().add("astra-workflow-empty");
                catalogList.getChildren().add(empty);
                return;
            }
            matches.forEach(block -> catalogList.getChildren().add(createCatalogCard(block)));
        }

        private Node createCatalogCard(WorkflowBlockCatalog.WorkflowBlock block) {
            VBox card = new VBox(Geometry.COMPACT_GAP);
            card.getStyleClass().add("astra-workflow-tool-card");
            card.setPadding(Geometry.CARD_INSETS);
            Label category = GuiText.label(GuiText.Role.PANEL_TEXT, block.category());
            category.getStyleClass().add("astra-workflow-tool-category");
            Label title = GuiText.label(GuiText.Role.PANEL_TEXT, block.label());
            title.getStyleClass().add("astra-workflow-tool-title");
            Label description = GuiText.label(GuiText.Role.PANEL_TEXT, block.description());
            description.getStyleClass().add("astra-workflow-tool-description");
            description.setWrapText(true);
            Button add = controlButton("Add", "astra-button-secondary");
            add.getStyleClass().add("astra-workflow-add-button");
            add.setOnAction(event -> addBlock(block, workflow.size()));
            HBox footer = new HBox();
            footer.setAlignment(Pos.CENTER_RIGHT);
            footer.getChildren().add(add);
            card.getChildren().addAll(category, title, description, footer);
            installCatalogDrag(card, block);
            return card;
        }

        private void installCatalogDrag(Node card, WorkflowBlockCatalog.WorkflowBlock block) {
            card.setOnDragDetected(event -> {
                Dragboard dragboard = card.startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.putString(CATALOG_DRAG_PREFIX + block.id());
                dragboard.setContent(content);
                event.consume();
            });
        }

        private void rebuildLane() {
            notebook.synchronize(workflow.size());
            lane.getChildren().clear();
            if (workflow.isEmpty()) {
                StackPane empty = new StackPane();
                empty.getStyleClass().add("astra-workflow-empty-lane");
                empty.setMinHeight(Geometry.EMPTY_LANE_HEIGHT);
                Label message = GuiText.label(
                        GuiText.Role.PANEL_TEXT,
                        "Drag tools here to build a workflow.");
                message.getStyleClass().add("astra-workflow-empty");
                empty.getChildren().add(message);
                installDropTarget(empty, 0);
                lane.getChildren().add(empty);
            } else {
                for (int index = 0; index <= workflow.size(); index++) {
                    lane.getChildren().add(createDropSlot(index));
                    if (index < workflow.size()) {
                        lane.getChildren().add(createLaneCard(workflow.get(index), index));
                    }
                }
            }
            refreshValidation();
        }

        private Node createDropSlot(int index) {
            StackPane slot = new StackPane();
            slot.getStyleClass().add("astra-workflow-drop-slot");
            slot.setMinHeight(Geometry.DROP_SLOT_HEIGHT);
            slot.setPrefHeight(Geometry.DROP_SLOT_HEIGHT);
            installDropTarget(slot, index);
            return slot;
        }

        private void installDropTarget(Node target, int insertionIndex) {
            target.setOnDragOver(event -> {
                if (dragText(event.getDragboard()).isPresent()) {
                    event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                    target.getStyleClass().add("astra-workflow-drop-slot-active");
                }
                event.consume();
            });
            target.setOnDragExited(event ->
                    target.getStyleClass().remove("astra-workflow-drop-slot-active"));
            target.setOnDragDropped(event -> {
                target.getStyleClass().remove("astra-workflow-drop-slot-active");
                Optional<String> payload = dragText(event.getDragboard());
                boolean completed = payload.map(value -> applyDrop(value, insertionIndex)).orElse(false);
                event.setDropCompleted(completed);
                event.consume();
            });
        }

        private boolean applyDrop(String payload, int insertionIndex) {
            if (payload.startsWith(CATALOG_DRAG_PREFIX)) {
                String id = payload.substring(CATALOG_DRAG_PREFIX.length());
                return catalog.stream()
                        .filter(block -> block.id().equals(id))
                        .findFirst()
                        .map(block -> {
                            addBlock(block, insertionIndex);
                            return true;
                        })
                        .orElse(false);
            }
            if (payload.startsWith(LANE_DRAG_PREFIX)) {
                try {
                    int sourceIndex = Integer.parseInt(payload.substring(LANE_DRAG_PREFIX.length()));
                    moveBlock(sourceIndex, insertionIndex);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return false;
        }

        private Node createLaneCard(WorkflowBlockCatalog.WorkflowBlock block, int index) {
            NotebookStep step = notebook.step(index);
            VBox card = new VBox(Geometry.COMPACT_GAP);
            card.setPadding(Geometry.CARD_INSETS);
            card.getStyleClass().addAll(
                    "astra-workflow-lane-card",
                    step.status().cardStyleClass);

            HBox row = new HBox(Geometry.INNER_GAP);
            row.setAlignment(Pos.CENTER_LEFT);

            Label number = GuiText.label(GuiText.Role.PANEL_TEXT, String.valueOf(index + 1));
            number.getStyleClass().addAll(
                    "astra-workflow-step-number",
                    step.status().numberStyleClass);
            VBox text = new VBox(Geometry.COMPACT_GAP);
            HBox.setHgrow(text, Priority.ALWAYS);
            Label title = GuiText.label(GuiText.Role.PANEL_TEXT, block.label());
            title.getStyleClass().add("astra-workflow-tool-title");
            Label description = GuiText.label(GuiText.Role.PANEL_TEXT, block.description());
            description.getStyleClass().add("astra-workflow-tool-description");
            description.setWrapText(true);
            text.getChildren().addAll(title, description);
            Node result = createStepResult(step, index);
            if (result != null) {
                text.getChildren().add(result);
            }

            Button up = compactButton("Up");
            up.setDisable(index == 0);
            up.setOnAction(event -> moveBlock(index, index - 1));
            Button down = compactButton("Down");
            down.setDisable(index == workflow.size() - 1);
            down.setOnAction(event -> moveBlock(index, index + 2));
            Button remove = compactButton("Remove");
            remove.getStyleClass().add("astra-button-danger");
            remove.setOnAction(event -> {
                workflow.remove(index);
                notebook.reset(workflow.size());
                rebuildLane();
            });
            row.getChildren().addAll(number, text, up, down, remove);
            card.getChildren().add(row);
            card.setOnDragDetected(event -> {
                Dragboard dragboard = card.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(LANE_DRAG_PREFIX + index);
                dragboard.setContent(content);
                event.consume();
            });
            return card;
        }

        private Node createStepResult(NotebookStep step, int index) {
            if (step.status() == StepStatus.PENDING) {
                return null;
            }
            HBox result = new HBox(Geometry.INNER_GAP);
            result.setAlignment(Pos.CENTER_LEFT);
            result.getStyleClass().add("astra-workflow-step-result");
            Label message = GuiText.label(
                    GuiText.Role.PANEL_TEXT,
                    step.message());
            message.setWrapText(true);
            message.getStyleClass().addAll(
                    "astra-workflow-step-message",
                    step.status().messageStyleClass);
            HBox.setHgrow(message, Priority.ALWAYS);
            result.getChildren().add(message);
            if (step.status() == StepStatus.FAILED) {
                Button resume = compactButton("Run from here");
                resume.getStyleClass().add("astra-workflow-resume-button");
                resume.setOnAction(event -> runFrom(index));
                result.getChildren().add(resume);
            }
            return result;
        }

        private void addBlock(WorkflowBlockCatalog.WorkflowBlock block, int insertionIndex) {
            int safeIndex = Math.max(0, Math.min(insertionIndex, workflow.size()));
            workflow.add(safeIndex, block);
            notebook.reset(workflow.size());
            rebuildLane();
            showStatus("'" + block.label() + "' added.", Status.INFO);
        }

        private void moveBlock(int sourceIndex, int insertionIndex) {
            if (sourceIndex < 0 || sourceIndex >= workflow.size()) {
                return;
            }
            WorkflowBlockCatalog.WorkflowBlock block = workflow.remove(sourceIndex);
            int adjusted = insertionIndex > sourceIndex ? insertionIndex - 1 : insertionIndex;
            int safeIndex = Math.max(0, Math.min(adjusted, workflow.size()));
            workflow.add(safeIndex, block);
            notebook.reset(workflow.size());
            rebuildLane();
        }

        private void refreshValidation() {
            WorkflowBlockCatalog.WorkflowValidation validation = WorkflowBlockCatalog.validate(workflow);
            int failedIndex = notebook.failedIndex();
            validationLabel.setText(failedIndex >= 0
                    ? "Resume from step " + (failedIndex + 1) + " when ready."
                    : validation.message());
            validationLabel.getStyleClass().removeAll(
                    "astra-workflow-validation-valid",
                    "astra-workflow-validation-invalid");
            validationLabel.getStyleClass().add(validation.valid() && failedIndex < 0
                    ? "astra-workflow-validation-valid"
                    : "astra-workflow-validation-invalid");
            runButton.setDisable(!validation.valid() || running());
        }

        private void run() {
            startRun(0, false);
        }

        private void runFrom(int startIndex) {
            startRun(startIndex, true);
        }

        private void startRun(int startIndex, boolean resume) {
            WorkflowBlockCatalog.WorkflowValidation validation = WorkflowBlockCatalog.validate(workflow);
            if (!validation.valid()) {
                showStatus(validation.message(), Status.WARNING);
                return;
            }
            if (startIndex < 0 || startIndex >= workflow.size()) {
                showStatus("The selected workflow step is no longer available.", Status.ERROR);
                return;
            }
            List<WorkflowBlockCatalog.WorkflowBlock> fullPlan = List.copyOf(workflow);
            List<WorkflowBlockCatalog.WorkflowBlock> runPlan =
                    List.copyOf(fullPlan.subList(startIndex, fullPlan.size()));
            if (!confirmRun(runPlan, resume, startIndex)) {
                showStatus("Workflow was not started.", Status.INFO);
                return;
            }
            if (resume) {
                appendLog("\nResuming from step " + (startIndex + 1) + ".\n");
            } else {
                technicalLog.clear();
            }
            notebook.prepareFrom(workflow.size(), startIndex);
            rebuildLane();
            running.set(true);
            progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            appendLog((resume ? "Workflow resumed with " : "Workflow started with ")
                    + runPlan.size() + " tool(s).\n");
            runningFuture = qupath.getThreadPoolManager()
                    .getSingleThreadExecutor(WorkflowBuilder.class)
                    .submit(() -> execute(runPlan, startIndex));
        }

        private void execute(
                List<WorkflowBlockCatalog.WorkflowBlock> runPlan,
                int startIndex) {
            String previousGuiRunActive = System.getProperty(GUI_RUN_ACTIVE_PROPERTY);
            System.setProperty(GUI_RUN_ACTIVE_PROPERTY, "true");
            int activeIndex = startIndex;
            try {
                for (int offset = 0; offset < runPlan.size(); offset++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("Workflow cancelled.");
                    }
                    activeIndex = startIndex + offset;
                    int stepIndex = activeIndex;
                    WorkflowBlockCatalog.WorkflowBlock block = runPlan.get(offset);
                    Platform.runLater(() -> {
                        notebook.markRunning(stepIndex);
                        rebuildLane();
                        showStatus(
                                "Running step " + (stepIndex + 1) + ": " + block.label(),
                                Status.RUNNING);
                    });
                    appendLog("\n[" + (stepIndex + 1) + "/" + workflow.size()
                            + "] " + block.label() + "\n");
                    executeTool(block);
                    Platform.runLater(() -> {
                        notebook.markSucceeded(stepIndex);
                        rebuildLane();
                    });
                }
                Platform.runLater(() -> {
                    progress.setProgress(1.0);
                    showStatus("Workflow completed successfully.", Status.SUCCESS);
                });
            } catch (CancellationException e) {
                int stoppedIndex = activeIndex;
                Platform.runLater(() -> {
                    notebook.markCancelled(stoppedIndex);
                    rebuildLane();
                    progress.setProgress(0.0);
                    showStatus("Workflow cancelled.", Status.WARNING);
                });
            } catch (Throwable error) {
                logger.error("Workflow builder execution failed.", error);
                appendLog("\nFAILED: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
                int failedIndex = activeIndex;
                String message = conciseMessage(error);
                Platform.runLater(() -> {
                    notebook.markFailed(failedIndex, message);
                    rebuildLane();
                    progress.setProgress(0.0);
                    showStatus(
                            "Workflow stopped at step " + (failedIndex + 1) + ": " + message,
                            Status.ERROR);
                });
            } finally {
                restoreProperty(previousGuiRunActive);
                Platform.runLater(() -> running.set(false));
            }
        }

        private void executeTool(WorkflowBlockCatalog.WorkflowBlock block)
                throws IOException, ScriptException {
            String script = loadScript(block);
            PrintWriter writer = new PrintWriter(new LogOutputStream(this::appendLog), true);
            ScriptParameters parameters = ScriptParameters.builder()
                    .setScript(script)
                    .setProject(qupath.getProject())
                    .setImageData(qupath.getImageData())
                    .setDefaultImports(QPEx.getCoreClasses())
                    .setDefaultStaticImports(Collections.singletonList(QPEx.class))
                    .setWriter(writer)
                    .setErrorWriter(writer)
                    .build();
            Object result = GroovyLanguage.getInstance().execute(parameters);
            if (result != null) {
                appendLog("Result: " + result + "\n");
            }
        }

        private String loadScript(WorkflowBlockCatalog.WorkflowBlock block) throws IOException {
            String scriptResource = block.scriptResource();
            try (InputStream stream = WorkflowBuilder.class.getClassLoader().getResourceAsStream(scriptResource)) {
                if (stream == null) {
                    throw new IOException("Missing bundled tool script: " + scriptResource);
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private boolean confirmRun(
                List<WorkflowBlockCatalog.WorkflowBlock> runPlan,
                boolean resume,
                int startIndex) {
            String orderedTools = java.util.stream.IntStream.range(0, runPlan.size())
                    .mapToObj(index -> (startIndex + index + 1) + ". "
                            + runPlan.get(index).label())
                    .collect(java.util.stream.Collectors.joining("\n"));
            Dialog<ButtonType> dialog = PipelineLauncher.createSuccessConfirmationDialog(
                    root.getScene() == null ? null : root.getScene().getWindow(),
                    resume ? "Resume Workflow" : "Run Workflow",
                    resume
                            ? "Resume here using manifest-defined defaults?"
                            : "Run these tools using their manifest-defined defaults?",
                    orderedTools
                            + (resume
                            ? "\n\nCompleted earlier steps will not run again. "
                            : "\n\n")
                            + "Each tool owns its own defaults. "
                            + "The Workflow Builder does not define or copy defaults "
                            + "from any catalog tool.");
            Node run = dialog.getDialogPane().lookupButton(ButtonType.OK);
            if (run instanceof Button button) {
                button.setText(resume ? "Run from here" : "Run Workflow");
            }
            return dialog.showAndWait().filter(ButtonType.OK::equals).isPresent();
        }

        private void showFailurePreview(int failureIndex) {
            notebook.prepareFrom(workflow.size(), 0);
            for (int index = 0; index < failureIndex; index++) {
                notebook.markSucceeded(index);
            }
            notebook.markFailed(
                    failureIndex,
                    "Detection channel 'DAPI' was not found. Choose an available channel.");
            rebuildLane();
            showStatus(
                    "Workflow stopped at step " + (failureIndex + 1) + ".",
                    Status.ERROR);
        }

        private void cancel() {
            Future<?> future = runningFuture;
            if (future != null && !future.isDone()) {
                future.cancel(true);
                showStatus("Cancellation requested.", Status.WARNING);
            } else {
                Stage stage = (Stage) root.getScene().getWindow();
                stage.close();
            }
        }

        private void updateRunningState(boolean active) {
            progress.setVisible(active);
            progress.setManaged(active);
            cancelButton.setText(active ? "Stop" : "Close");
            runButton.setDisable(active || !WorkflowBlockCatalog.validate(workflow).valid());
            lane.setDisable(active);
            catalogList.setDisable(active);
        }

        private void showStatus(String message, Status status) {
            statusLabel.setText(message == null ? "" : message);
            statusLabel.getStyleClass().removeAll(
                    "astra-workflow-status-info",
                    "astra-workflow-status-running",
                    "astra-workflow-status-success",
                    "astra-workflow-status-warning",
                    "astra-workflow-status-error");
            statusLabel.getStyleClass().add(status.styleClass);
        }

        private void appendLog(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (Platform.isFxApplicationThread()) {
                technicalLog.appendText(text);
            } else {
                Platform.runLater(() -> technicalLog.appendText(text));
            }
        }

        private void dispose() {
            Future<?> future = runningFuture;
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }

        private static Optional<String> dragText(Dragboard dragboard) {
            return dragboard != null && dragboard.hasString()
                    ? Optional.ofNullable(dragboard.getString())
                    : Optional.empty();
        }

        private static Button compactButton(String text) {
            Button button = controlButton(text, "astra-button-small");
            button.setMinHeight(Geometry.CONTROL_HEIGHT);
            button.setPrefHeight(Geometry.CONTROL_HEIGHT);
            return button;
        }

        private static void configureMacroButton(Button button) {
            button.setMinWidth(Geometry.MACRO_BUTTON_WIDTH);
            button.setPrefWidth(Geometry.MACRO_BUTTON_WIDTH);
            button.setMinHeight(Geometry.CONTROL_HEIGHT);
            button.setPrefHeight(Geometry.CONTROL_HEIGHT);
        }

        private enum Status {
            INFO("astra-workflow-status-info"),
            RUNNING("astra-workflow-status-running"),
            SUCCESS("astra-workflow-status-success"),
            WARNING("astra-workflow-status-warning"),
            ERROR("astra-workflow-status-error");

            private final String styleClass;

            Status(String styleClass) {
                this.styleClass = styleClass;
            }
        }
    }

    private static Button controlButton(String text, String roleClass) {
        Button button = GuiText.button(GuiText.Role.CONTROL_TEXT, text);
        button.getStyleClass().addAll("astra-button", roleClass);
        return button;
    }

    private static String conciseMessage(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage()).trim();
        if (message.isBlank()) {
            return error == null ? "Unexpected error." : error.getClass().getSimpleName();
        }
        return message.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(error == null ? "Unexpected error." : error.getClass().getSimpleName());
    }

    private static void restoreProperty(String previousValue) {
        if (previousValue == null) {
            System.clearProperty(GUI_RUN_ACTIVE_PROPERTY);
        } else {
            System.setProperty(GUI_RUN_ACTIVE_PROPERTY, previousValue);
        }
    }

    enum StepStatus {
        PENDING(
                "astra-workflow-step-pending",
                "astra-workflow-step-number-pending",
                "astra-workflow-step-message-pending"),
        RUNNING(
                "astra-workflow-step-running",
                "astra-workflow-step-number-running",
                "astra-workflow-step-message-running"),
        SUCCESS(
                "astra-workflow-step-success",
                "astra-workflow-step-number-success",
                "astra-workflow-step-message-success"),
        FAILED(
                "astra-workflow-step-failed",
                "astra-workflow-step-number-failed",
                "astra-workflow-step-message-failed"),
        SKIPPED(
                "astra-workflow-step-skipped",
                "astra-workflow-step-number-skipped",
                "astra-workflow-step-message-skipped"),
        CANCELLED(
                "astra-workflow-step-cancelled",
                "astra-workflow-step-number-cancelled",
                "astra-workflow-step-message-cancelled");

        private final String cardStyleClass;
        private final String numberStyleClass;
        private final String messageStyleClass;

        StepStatus(
                String cardStyleClass,
                String numberStyleClass,
                String messageStyleClass) {
            this.cardStyleClass = cardStyleClass;
            this.numberStyleClass = numberStyleClass;
            this.messageStyleClass = messageStyleClass;
        }
    }

    record NotebookStep(StepStatus status, String message) {

        NotebookStep {
            status = Objects.requireNonNull(status, "status");
            message = message == null ? "" : message;
        }

        static NotebookStep pending() {
            return new NotebookStep(StepStatus.PENDING, "");
        }
    }

    static final class NotebookRunState {

        private final List<NotebookStep> steps = new ArrayList<>();

        void synchronize(int stepCount) {
            if (steps.size() != stepCount) {
                reset(stepCount);
            }
        }

        void reset(int stepCount) {
            if (stepCount < 0) {
                throw new IllegalArgumentException("Step count must not be negative.");
            }
            steps.clear();
            for (int index = 0; index < stepCount; index++) {
                steps.add(NotebookStep.pending());
            }
        }

        void prepareFrom(int stepCount, int startIndex) {
            synchronize(stepCount);
            requireIndex(startIndex);
            for (int index = startIndex; index < steps.size(); index++) {
                steps.set(index, NotebookStep.pending());
            }
        }

        NotebookStep step(int index) {
            requireIndex(index);
            return steps.get(index);
        }

        List<NotebookStep> snapshot() {
            return List.copyOf(steps);
        }

        int failedIndex() {
            for (int index = 0; index < steps.size(); index++) {
                if (steps.get(index).status() == StepStatus.FAILED) {
                    return index;
                }
            }
            return -1;
        }

        void markRunning(int index) {
            set(index, StepStatus.RUNNING, "Running...");
        }

        void markSucceeded(int index) {
            set(index, StepStatus.SUCCESS, "Completed");
        }

        void markFailed(int index, String message) {
            set(index, StepStatus.FAILED, conciseMessage(
                    new IllegalStateException(message)));
            markDownstreamSkipped(index);
        }

        void markCancelled(int index) {
            set(index, StepStatus.CANCELLED, "Stopped");
            markDownstreamSkipped(index);
        }

        private void markDownstreamSkipped(int index) {
            for (int downstream = index + 1; downstream < steps.size(); downstream++) {
                steps.set(
                        downstream,
                        new NotebookStep(StepStatus.SKIPPED, "Not run"));
            }
        }

        private void set(int index, StepStatus status, String message) {
            requireIndex(index);
            steps.set(index, new NotebookStep(status, message));
        }

        private void requireIndex(int index) {
            if (index < 0 || index >= steps.size()) {
                throw new IndexOutOfBoundsException(
                        "Workflow step index " + index
                                + " is outside 0.." + (steps.size() - 1) + ".");
            }
        }
    }

    private static final class LogOutputStream extends OutputStream {

        private final java.util.function.Consumer<String> sink;
        private final StringBuilder buffer = new StringBuilder();

        private LogOutputStream(java.util.function.Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public void write(int value) {
            char character = (char) value;
            buffer.append(character);
            if (character == '\n') {
                flush();
            }
        }

        @Override
        public void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            sink.accept(buffer.toString());
            buffer.setLength(0);
        }
    }

    private static final class Geometry {

        private static final double UNIT = LauncherGeometryTokens.LAYOUT_UNIT;
        private static final double OUTER = LauncherGeometryTokens.OUTER_MARGIN;
        private static final double INNER = LauncherGeometryTokens.INTRA_PANEL_MARGIN;
        private static final double COMPACT_GAP = LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP;
        private static final double INNER_GAP = LauncherGeometryTokens.INTRA_PANEL_SUBTLE_GAP;
        private static final double PANEL_GAP = OUTER;
        private static final double WINDOW_WIDTH = UNIT * 52.0;
        private static final double WINDOW_HEIGHT = UNIT * 38.0;
        private static final double WINDOW_MIN_WIDTH = UNIT * 40.0;
        private static final double WINDOW_MIN_HEIGHT = UNIT * 28.0;
        private static final double HEADER_HEIGHT = UNIT * 5.0;
        private static final double CATALOG_WIDTH = UNIT * 14.0;
        private static final double CONTROL_HEIGHT = LauncherGeometryTokens.BUTTON_HEIGHT;
        private static final double MACRO_BUTTON_WIDTH = UNIT * 5.0;
        private static final double PROGRESS_WIDTH = UNIT * 8.0;
        private static final double EMPTY_LANE_HEIGHT = UNIT * 14.0;
        private static final double DROP_SLOT_HEIGHT = INNER_GAP;
        private static final int LOG_ROW_COUNT =
                (int) Math.round(LauncherGeometryTokens.BILATERAL_EDGE_COUNT
                        + LauncherGeometryTokens.SINGLE_COUNT);
        private static final Insets OUTER_INSETS = LauncherGeometryTokens.uniformOuterMargin();
        private static final Insets PANEL_INSETS = LauncherGeometryTokens.intraPanelPadding();
        private static final Insets CARD_INSETS = LauncherGeometryTokens.intraPanelPadding();
        private static final Insets FOOTER_INSETS = new Insets(0.0, OUTER, OUTER, OUTER);

        private Geometry() {
        }
    }
}
