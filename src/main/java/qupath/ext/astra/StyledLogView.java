package qupath.ext.astra;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;
import java.util.Map;

final class StyledLogView extends VBox {

    private static final String FONT_STACK = "\"Inter\", \"Avenir Next\", \"Segoe UI\", sans-serif";
    private static final String MONO_FONT_STACK = "\"JetBrains Mono\", \"SFMono-Regular\", \"Consolas\", monospace";

    private final VBox entries = new VBox(7.0);
    private final StringBuilder plainText = new StringBuilder();
    private final StringBuilder rawText = new StringBuilder();
    private final ScrollPane scroll;
    private final RunTimelineModel timeline = new RunTimelineModel();
    private final RunProgressTracker progressTracker = new RunProgressTracker();
    private final RunLogBlockAccumulator blockAccumulator = new RunLogBlockAccumulator();
    private final Label statusTitle = new Label("Ready");
    private final Label statusDetail = new Label("Waiting for an ASTRA run.");
    private final Button warningsChip = new Button("Warnings 0");
    private final Button errorsChip = new Button("Errors 0");
    private final VBox failureSummary = new VBox(5.0);
    private final HBox timelineRail = new HBox(6.0);
    private RunLogSource currentSource;
    private VBox currentGroupBody;
    private VBox currentHiddenBody;
    private Button currentHiddenToggle;
    private HBox currentProgressRow;
    private String lastRenderedProgress = "";
    private int currentCellposeVisibleCount;
    private Region firstWarningTarget;
    private Region firstErrorTarget;
    private boolean autoScroll = true;

    StyledLogView() {
        super(7.0);
        setStyle("-fx-background-color: #061720; -fx-border-color: #355b69; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 8;");

        Button copy = new Button("Copy All");
        copy.setFocusTraversable(false);
        copy.setStyle(copyLogButtonStyle());
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(plainText.toString());
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText("Copied");
            copy.setStyle(copiedLogButtonStyle());
            PauseTransition reset = new PauseTransition(Duration.seconds(1.2));
            reset.setOnFinished(done -> {
                copy.setText("Copy All");
                copy.setStyle(copyLogButtonStyle());
            });
            reset.play();
        });

        HBox toolbar = new HBox(8.0, copy);
        toolbar.setAlignment(Pos.CENTER_RIGHT);

        styleCountChip(warningsChip, RunLogSeverity.WARNING);
        styleCountChip(errorsChip, RunLogSeverity.ERROR);
        warningsChip.setVisible(false);
        warningsChip.setManaged(false);
        errorsChip.setVisible(false);
        errorsChip.setManaged(false);
        warningsChip.setOnAction(event -> scrollToTarget(firstWarningTarget));
        errorsChip.setOnAction(event -> scrollToTarget(firstErrorTarget));

        HBox statusLine = new HBox(8.0, statusDetail, warningsChip, errorsChip);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusDetail, Priority.ALWAYS);

        failureSummary.setVisible(false);
        failureSummary.setManaged(false);
        failureSummary.setPadding(new Insets(9.0, 10.0, 10.0, 10.0));
        failureSummary.setStyle("-fx-background-color: #341a18; -fx-border-color: #b9675b; -fx-border-radius: 6; -fx-background-radius: 6;");

        VBox statusContent = new VBox(6.0, statusTitle, statusLine, timelineRail);
        statusContent.setPadding(new Insets(9.0, 10.0, 10.0, 10.0));
        statusContent.setStyle("-fx-background-color: #0d2430; -fx-border-color: #375f6c; -fx-border-radius: 6; -fx-background-radius: 6;");
        statusTitle.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12.5px; -fx-font-weight: 900; -fx-text-fill: #ecfbf7;");
        statusDetail.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: #a9c8ce;");
        timelineRail.setAlignment(Pos.CENTER_LEFT);

        entries.setFillWidth(true);
        scroll = new ScrollPane(entries);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(520.0);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scroll.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            autoScroll = newValue.doubleValue() >= 0.985d;
        });
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(statusContent, failureSummary, toolbar, scroll);
    }

    void beginRun(String scriptName, String configuredScript) {
        clear();
        timeline.start(scriptName, configuredScript);
        renderTimeline();
    }

    void appendMessage(RunLogSource source, RunLogSeverity severity, String text) {
        appendEntries(RunLogParser.parse(text, source, severity), text);
    }

    void appendText(String text, RunLogSource defaultSource, RunLogSeverity defaultSeverity) {
        appendEntries(RunLogParser.parse(text, defaultSource, defaultSeverity), text);
    }

    void clear() {
        plainText.setLength(0);
        rawText.setLength(0);
        entries.getChildren().clear();
        progressTracker.reset();
        blockAccumulator.reset();
        currentSource = null;
        currentGroupBody = null;
        currentHiddenBody = null;
        currentHiddenToggle = null;
        currentProgressRow = null;
        lastRenderedProgress = "";
        currentCellposeVisibleCount = 0;
        firstWarningTarget = null;
        firstErrorTarget = null;
        failureSummary.getChildren().clear();
        failureSummary.setVisible(false);
        failureSummary.setManaged(false);
        autoScroll = true;
    }

    String plainTextForTest() {
        return plainText.toString();
    }

    String rawTextForFutureCopyRaw() {
        return rawText.toString();
    }

    private void appendEntries(List<RunLogEntry> parsed, String rawInput) {
        if (rawInput != null && !rawInput.isEmpty()) {
            rawText.append(rawInput);
        }
        for (RunLogEntry entry : parsed) {
            String copy = entry.copyText();
            if (!copy.isBlank()) {
                if (!plainText.isEmpty()) {
                    plainText.append('\n');
                }
                plainText.append(copy);
            }

            RunLogEvent event = RunLogPresenter.eventFor(entry);
            timeline.accept(event);
            progressTracker.accept(event);
            renderTimeline();
            boolean interruptsPipelineBlock = RunLogPresenter.isCommand(entry)
                    || entry.source() == RunLogSource.CELLPOSE
                    || entry.source() == RunLogSource.PYTHON
                    || entry.source() == RunLogSource.SCRIPT;
            if (interruptsPipelineBlock) {
                flushPipelineBlock();
            }
            if (RunLogPresenter.isCommand(entry)) {
                appendStandalone(createCommandBlock(entry), entry);
                continue;
            }
            if (entry.source() == RunLogSource.ASTRA || blockAccumulator.isCapturing()) {
                var renderedBlock = blockAccumulator.accept(entry);
                if (renderedBlock.isPresent()) {
                    appendRenderedBlock(renderedBlock.get());
                    continue;
                }
                if (blockAccumulator.isCapturing()) {
                    continue;
                }
            }
            if (entry.source() == RunLogSource.CELLPOSE && entry.kind() == RunLogKind.PROGRESS) {
                appendProgressLine(entry);
                continue;
            }
            if (isRedundantProgress(entry)) {
                continue;
            }
            if (RunLogPresenter.isStageCard(entry)) {
                appendStandalone(createStageCard(entry, event), entry);
                continue;
            }
            if (entry.kind() == RunLogKind.KEY_VALUE && entry.source() == RunLogSource.ASTRA) {
                appendStandalone(createKeyValueCard(entry), entry);
                continue;
            }
            appendRawLine(entry);
        }
        if (autoScroll) {
            scroll.setVvalue(1.0d);
        }
    }

    private boolean isRedundantProgress(RunLogEntry entry) {
        if (entry.kind() != RunLogKind.PROGRESS) {
            lastRenderedProgress = "";
            return false;
        }
        String normalized = entry.text().replaceAll("\\s+", " ");
        boolean redundant = normalized.equals(lastRenderedProgress);
        lastRenderedProgress = normalized;
        return redundant;
    }

    private void flushPipelineBlock() {
        blockAccumulator.flush().ifPresent(this::appendRenderedBlock);
    }

    private void appendRenderedBlock(RunLogRenderedBlock block) {
        appendStandalone(createRenderedBlockCard(block), block.entries().isEmpty() ? null : block.entries().get(0));
        block.entries().stream()
                .filter(entry -> entry.severity() == RunLogSeverity.ERROR || entry.severity() == RunLogSeverity.WARNING)
                .findFirst()
                .ifPresent(entry -> registerSeverityTarget(entry, (Region) entries.getChildren().get(entries.getChildren().size() - 1), RunLogPresenter.eventFor(entry)));
    }

    private void startSourceGroup(RunLogSource source) {
        currentSource = source;
        VBox wrapper = new VBox(0.0);
        wrapper.setFillWidth(true);

        Label tab = new Label(source.displayName());
        tab.setStyle(sourceTabStyle(source));
        VBox.setMargin(tab, new Insets(0.0, 0.0, 0.0, 9.0));

        currentGroupBody = new VBox(4.0);
        currentGroupBody.setPadding(new Insets(8.0, 9.0, 9.0, 9.0));
        currentGroupBody.setStyle(sourceBlockStyle(source));
        wrapper.getChildren().addAll(tab, currentGroupBody);
        entries.getChildren().add(wrapper);
        currentHiddenBody = null;
        currentHiddenToggle = null;
        currentCellposeVisibleCount = 0;
        currentProgressRow = null;
    }

    private void appendRawLine(RunLogEntry entry) {
        if (entry.source() != currentSource || currentGroupBody == null) {
            startSourceGroup(entry.source());
        }
        HBox line = createLine(entry);
        if (shouldCollapseInCurrentGroup(entry)) {
            ensureHiddenBody();
            currentHiddenBody.getChildren().add(line);
        } else {
            currentGroupBody.getChildren().add(line);
        }
        registerSeverityTarget(entry, line, RunLogPresenter.eventFor(entry));
    }

    private void appendProgressLine(RunLogEntry entry) {
        if (entry.source() != currentSource || currentGroupBody == null) {
            startSourceGroup(entry.source());
        }
        if (currentProgressRow != null) {
            currentGroupBody.getChildren().remove(currentProgressRow);
        }
        currentProgressRow = createLine(entry);
        currentGroupBody.getChildren().add(currentProgressRow);
    }

    private void appendStandalone(Region node, RunLogEntry entry) {
        currentSource = null;
        currentGroupBody = null;
        currentHiddenBody = null;
        currentHiddenToggle = null;
        currentCellposeVisibleCount = 0;
        currentProgressRow = null;
        entries.getChildren().add(node);
        if (entry != null) {
            registerSeverityTarget(entry, node, RunLogPresenter.eventFor(entry));
        }
    }

    private boolean shouldCollapseInCurrentGroup(RunLogEntry entry) {
        if (entry.source() != RunLogSource.CELLPOSE || entry.severity() == RunLogSeverity.ERROR || entry.severity() == RunLogSeverity.WARNING) {
            return false;
        }
        currentCellposeVisibleCount++;
        return currentCellposeVisibleCount > 8 || RunLogPresenter.isNoisyCellposeDetail(entry);
    }

    private void ensureHiddenBody() {
        if (currentHiddenBody != null) {
            return;
        }
        currentHiddenBody = new VBox(4.0);
        currentHiddenBody.setVisible(false);
        currentHiddenBody.setManaged(false);
        currentHiddenToggle = new Button("Show Cellpose details");
        currentHiddenToggle.setFocusTraversable(false);
        currentHiddenToggle.setStyle(disclosureButtonStyle());
        currentHiddenToggle.setOnAction(event -> {
            boolean show = !currentHiddenBody.isVisible();
            currentHiddenBody.setVisible(show);
            currentHiddenBody.setManaged(show);
            currentHiddenToggle.setText(show ? "Hide Cellpose details" : "Show Cellpose details");
        });
        currentGroupBody.getChildren().add(currentHiddenToggle);
        currentGroupBody.getChildren().add(currentHiddenBody);
    }

    private HBox createLine(RunLogEntry entry) {
        HBox row = new HBox(7.0);
        row.setAlignment(Pos.TOP_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);

        Region accent = new Region();
        accent.setMinWidth(3.0);
        accent.setPrefWidth(3.0);
        accent.setMaxWidth(3.0);
        accent.setMinHeight(16.0);
        accent.setStyle("-fx-background-color: " + severityAccent(entry.severity()) + "; -fx-background-radius: 4;");

        Label text = new Label(RunLogPresenter.shortDisplayText(entry.text()));
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        text.setStyle(lineTextStyle(entry));
        HBox.setHgrow(text, Priority.ALWAYS);

        if (showsSeverityBadge(entry.severity())) {
            Label badge = new Label(entry.severity().displayName());
            badge.setMinWidth(58.0);
            badge.setAlignment(Pos.CENTER);
            badge.setStyle(severityBadgeStyle(entry.severity()));
            row.getChildren().addAll(accent, badge, text);
        } else {
            row.getChildren().addAll(accent, text);
        }
        return row;
    }

    private VBox createStageCard(RunLogEntry entry, RunLogEvent event) {
        VBox card = new VBox(6.0);
        card.setPadding(new Insets(9.0, 10.0, 10.0, 10.0));
        card.setStyle(cardStyle(entry.severity()));
        Label title = new Label(RunLogPresenter.shortDisplayText(entry.text()));
        title.setWrapText(true);
        title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 900; -fx-text-fill: #eefaf6;");
        card.getChildren().add(title);
        Map<String, String> badges = event == null ? Map.of() : event.metrics();
        HBox badgeRow = badgeRow(badges);
        if (!badgeRow.getChildren().isEmpty()) {
            card.getChildren().add(badgeRow);
        }
        return card;
    }

    private VBox createKeyValueCard(RunLogEntry entry) {
        VBox card = new VBox(5.0);
        card.setPadding(new Insets(7.0, 9.0, 7.0, 9.0));
        card.setStyle("-fx-background-color: #102733; -fx-border-color: #2f5360; -fx-border-radius: 5; -fx-background-radius: 5;");
        RunLogMetrics.keyValue(entry).ifPresent(kv -> {
            HBox row = new HBox(8.0);
            row.setAlignment(Pos.BASELINE_LEFT);
            Label key = new Label(kv.key());
            key.setMinWidth(118.0);
            key.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 900; -fx-text-fill: #8fb8c0;");
            Label value = new Label(RunLogPresenter.shortDisplayText(kv.value()));
            value.setWrapText(true);
            value.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #dcebed;");
            HBox.setHgrow(value, Priority.ALWAYS);
            row.getChildren().addAll(key, value);
            card.getChildren().add(row);
        });
        HBox badgeRow = badgeRow(RunLogMetrics.badges(entry));
        if (!badgeRow.getChildren().isEmpty()) {
            card.getChildren().add(badgeRow);
        }
        return card;
    }

    private VBox createRenderedBlockCard(RunLogRenderedBlock block) {
        VBox card = new VBox(6.0);
        card.setPadding(new Insets(10.0, 11.0, 11.0, 11.0));
        card.setStyle(cardStyle(block.severity()));
        Label title = new Label(RunLogPresenter.shortDisplayText(block.title()));
        title.setWrapText(true);
        title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 900; -fx-text-fill: #eefaf6;");
        card.getChildren().add(title);
        if (!block.subtitle().isBlank()) {
            Label subtitle = new Label(RunLogPresenter.shortDisplayText(block.subtitle()));
            subtitle.setWrapText(true);
            subtitle.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 10.8px; -fx-font-weight: 600; -fx-text-fill: #bcd3d8;");
            card.getChildren().add(subtitle);
        }
        for (RunLogKeyValue kv : block.keyValues()) {
            card.getChildren().add(createKeyValueRow(kv));
        }
        HBox badgeRow = badgeRow(block.metrics());
        if (!badgeRow.getChildren().isEmpty()) {
            card.getChildren().add(badgeRow);
        }
        return card;
    }

    private HBox createKeyValueRow(RunLogKeyValue kv) {
        HBox row = new HBox(8.0);
        row.setAlignment(Pos.BASELINE_LEFT);
        Label key = new Label(kv.key());
        key.setMinWidth(132.0);
        key.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 900; -fx-text-fill: #8fb8c0;");
        Label value = new Label(RunLogPresenter.shortDisplayText(kv.value()));
        value.setWrapText(true);
        value.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #dcebed;");
        HBox.setHgrow(value, Priority.ALWAYS);
        row.getChildren().addAll(key, value);
        return row;
    }

    private VBox createCommandBlock(RunLogEntry entry) {
        VBox card = new VBox(5.0);
        card.setPadding(new Insets(8.0, 9.0, 9.0, 9.0));
        card.setStyle("-fx-background-color: #121d27; -fx-border-color: #586879; -fx-border-radius: 5; -fx-background-radius: 5;");
        Label title = new Label("Command");
        title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 900; -fx-text-fill: #b9c7cf;");
        Label command = new Label(RunLogPresenter.shortDisplayText(entry.text()));
        command.setWrapText(true);
        command.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 11px; -fx-font-weight: 600; -fx-text-fill: #e6edf2;");
        card.getChildren().addAll(title, command);
        return card;
    }

    private HBox badgeRow(Map<String, String> badges) {
        HBox row = new HBox(5.0);
        row.setAlignment(Pos.CENTER_LEFT);
        if (badges == null) {
            return row;
        }
        badges.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                Label badge = new Label(key + " " + RunLogPresenter.shortDisplayText(value));
                badge.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 9.5px; -fx-font-weight: 900; -fx-text-fill: #06202b; -fx-background-color: #b9efe6; -fx-background-radius: 999; -fx-padding: 2 7 2 7;");
                row.getChildren().add(badge);
            }
        });
        return row;
    }

    private void renderTimeline() {
        statusTitle.setText(timeline.statusTitle());
        statusDetail.setText(timeline.statusDetail(progressTracker.detail()));
        warningsChip.setText("Warnings " + timeline.warningCount());
        errorsChip.setText("Errors " + timeline.errorCount());
        warningsChip.setVisible(timeline.warningCount() > 0);
        warningsChip.setManaged(timeline.warningCount() > 0);
        errorsChip.setVisible(timeline.errorCount() > 0);
        errorsChip.setManaged(timeline.errorCount() > 0);
        timelineRail.getChildren().clear();
        for (RunTimelineStep step : timeline.steps()) {
            timelineRail.getChildren().add(timelineNode(step));
        }
    }

    private HBox timelineNode(RunTimelineStep step) {
        HBox node = new HBox(4.0);
        node.setAlignment(Pos.CENTER_LEFT);
        Region dot = new Region();
        dot.setMinSize(8.0, 8.0);
        dot.setPrefSize(8.0, 8.0);
        dot.setMaxSize(8.0, 8.0);
        dot.setStyle("-fx-background-color: " + timelineColor(step.state()) + "; -fx-background-radius: 999;");
        Label label = new Label(step.label());
        label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10px; -fx-font-weight: 900; -fx-text-fill: " + timelineTextColor(step.state()) + ";");
        node.getChildren().addAll(dot, label);
        if (!step.durationLabel().isBlank() && step.state() != RunTimelineState.PENDING) {
            Label duration = new Label(step.durationLabel());
            duration.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 9px; -fx-font-weight: 800; -fx-text-fill: #8fb8c0;");
            node.getChildren().add(duration);
        }
        return node;
    }

    private void registerSeverityTarget(RunLogEntry entry, Region node, RunLogEvent event) {
        if (entry == null || node == null) {
            return;
        }
        if (entry.severity() == RunLogSeverity.ERROR && firstErrorTarget == null) {
            firstErrorTarget = node;
            renderFailureAdvice(RunLogErrorAdvisor.advise(event));
        } else if (entry.severity() == RunLogSeverity.WARNING && firstWarningTarget == null) {
            firstWarningTarget = node;
        }
    }

    private void renderFailureAdvice(RunLogErrorAdvice advice) {
        if (advice == null) {
            return;
        }
        failureSummary.getChildren().clear();
        Label title = new Label(advice.family());
        title.setWrapText(true);
        title.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 12px; -fx-font-weight: 900; -fx-text-fill: #ffe1dc;");
        Label message = new Label(RunLogPresenter.shortDisplayText(advice.message()));
        message.setWrapText(true);
        message.setStyle("-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 10.8px; -fx-font-weight: 600; -fx-text-fill: #ffd3cb;");
        failureSummary.getChildren().addAll(title, message);
        failureSummary.getChildren().add(createAdviceRow("Likely cause", advice.likelyCause()));
        failureSummary.getChildren().add(createAdviceRow("Next action", advice.nextAction()));
        String context = advice.source().displayName() + (advice.stage().isBlank() ? "" : " | " + advice.stage());
        failureSummary.getChildren().add(createAdviceRow("Source", context));
        failureSummary.setVisible(true);
        failureSummary.setManaged(true);
    }

    private HBox createAdviceRow(String labelText, String valueText) {
        HBox row = new HBox(8.0);
        row.setAlignment(Pos.BASELINE_LEFT);
        Label label = new Label(labelText);
        label.setMinWidth(86.0);
        label.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10px; -fx-font-weight: 900; -fx-text-fill: #ffad9f;");
        Label value = new Label(RunLogPresenter.shortDisplayText(valueText));
        value.setWrapText(true);
        value.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 700; -fx-text-fill: #ffe9e5;");
        HBox.setHgrow(value, Priority.ALWAYS);
        row.getChildren().addAll(label, value);
        return row;
    }

    private void scrollToTarget(Region target) {
        if (target == null || entries.getChildren().isEmpty()) {
            return;
        }
        target.setVisible(true);
        target.setManaged(true);
        double contentHeight = Math.max(1.0d, entries.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight());
        double y = Math.max(0.0d, target.getBoundsInParent().getMinY());
        scroll.setVvalue(Math.max(0.0d, Math.min(1.0d, y / contentHeight)));
        target.requestFocus();
    }

    private static boolean showsSeverityBadge(RunLogSeverity severity) {
        return severity == RunLogSeverity.ERROR
                || severity == RunLogSeverity.WARNING
                || severity == RunLogSeverity.SUCCESS
                || severity == RunLogSeverity.CANCELLED;
    }

    private static void styleCountChip(Button chip, RunLogSeverity severity) {
        chip.setFocusTraversable(false);
        chip.setStyle("-fx-font-family: " + FONT_STACK + "; -fx-font-size: 9.5px; -fx-font-weight: 900;"
                + " -fx-text-fill: #061720; -fx-padding: 2 7 2 7;"
                + " -fx-background-color: " + severityAccent(severity) + ";"
                + " -fx-border-color: transparent; -fx-background-radius: 999;");
    }

    private static String lineTextStyle(RunLogEntry entry) {
        String color = switch (entry.severity()) {
            case ERROR -> "#ffd3cb";
            case WARNING -> "#ffe0a3";
            case SUCCESS -> "#c5f2d2";
            case CANCELLED -> "#ffe8b8";
            case DEBUG -> "#9fb3bd";
            default -> "#dcebed";
        };
        String weight = entry.kind() == RunLogKind.SEPARATOR ? "800" : "500";
        String opacity = entry.kind() == RunLogKind.SEPARATOR ? "0.72" : "1.0";
        return "-fx-font-family: " + MONO_FONT_STACK + "; -fx-font-size: 11.5px; -fx-font-weight: " + weight
                + "; -fx-text-fill: " + color + "; -fx-opacity: " + opacity + ";";
    }

    private static String sourceTabStyle(RunLogSource source) {
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 900;"
                + " -fx-text-fill: #061720; -fx-padding: 3 9 3 9;"
                + " -fx-background-color: " + sourceAccent(source) + ";"
                + " -fx-background-radius: 5 5 0 0;"
                + " -fx-border-color: " + sourceBorder(source) + ";"
                + " -fx-border-radius: 5 5 0 0;";
    }

    private static String sourceBlockStyle(RunLogSource source) {
        return "-fx-background-color: " + sourceBackground(source) + ";"
                + " -fx-border-color: " + sourceBorder(source) + ";"
                + " -fx-border-radius: 6; -fx-background-radius: 6;";
    }

    private static String severityBadgeStyle(RunLogSeverity severity) {
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 9.5px; -fx-font-weight: 900;"
                + " -fx-text-fill: #061720; -fx-padding: 2 5 2 5;"
                + " -fx-background-color: " + severityAccent(severity) + ";"
                + " -fx-background-radius: 4;";
    }

    private static String severityAccent(RunLogSeverity severity) {
        return switch (severity) {
            case ERROR -> "#ff8f7f";
            case WARNING -> "#ffd166";
            case SUCCESS -> "#8ee6a8";
            case CANCELLED -> "#f4c56a";
            case DEBUG -> "#7d92a0";
            case INFO -> "#6fd6cb";
            case NEUTRAL -> "#7ca1ad";
        };
    }

    private static String cardStyle(RunLogSeverity severity) {
        String border = switch (severity) {
            case ERROR -> "#b9675b";
            case WARNING -> "#a98234";
            case SUCCESS -> "#4d9a66";
            case CANCELLED -> "#a98234";
            default -> "#3b6670";
        };
        return "-fx-background-color: #0e2a34; -fx-border-color: " + border + "; -fx-border-radius: 6; -fx-background-radius: 6;";
    }

    private static String timelineColor(RunTimelineState state) {
        return switch (state) {
            case ACTIVE -> "#6fd6cb";
            case COMPLETE -> "#8ee6a8";
            case WARNING -> "#ffd166";
            case ERROR -> "#ff8f7f";
            case CANCELLED -> "#f4c56a";
            case PENDING -> "#49636b";
        };
    }

    private static String timelineTextColor(RunTimelineState state) {
        return switch (state) {
            case ACTIVE, COMPLETE -> "#e7fbf5";
            case WARNING -> "#ffe0a3";
            case ERROR -> "#ffd3cb";
            case CANCELLED -> "#ffe8b8";
            case PENDING -> "#9eb9bf";
        };
    }

    private static String disclosureButtonStyle() {
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10px; -fx-font-weight: 900;"
                + " -fx-background-color: #223846; -fx-text-fill: #cfe2e6;"
                + " -fx-border-color: #405b68; -fx-border-radius: 4; -fx-background-radius: 4;";
    }

    private static String sourceAccent(RunLogSource source) {
        return switch (source) {
            case ASTRA -> "#64d7c7";
            case QUPATH -> "#9fc4ff";
            case CELLPOSE -> "#c5adff";
            case PYTHON -> "#f5cf75";
            case SCRIPT -> "#b9c7cf";
            case SYSTEM -> "#9ee0b8";
        };
    }

    private static String sourceBorder(RunLogSource source) {
        return switch (source) {
            case ASTRA -> "#2f8077";
            case QUPATH -> "#4e6f9d";
            case CELLPOSE -> "#755fa6";
            case PYTHON -> "#967234";
            case SCRIPT -> "#61727b";
            case SYSTEM -> "#4b8760";
        };
    }

    private static String sourceBackground(RunLogSource source) {
        return switch (source) {
            case ASTRA -> "#0c2a2d";
            case QUPATH -> "#0d2235";
            case CELLPOSE -> "#191f37";
            case PYTHON -> "#2c2412";
            case SCRIPT -> "#172630";
            case SYSTEM -> "#102a1f";
        };
    }

    private static String copyLogButtonStyle() {
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 900;"
                + " -fx-background-color: #163748; -fx-text-fill: #eaf7f4;"
                + " -fx-border-color: #4d7583; -fx-border-radius: 4; -fx-background-radius: 4;";
    }

    private static String copiedLogButtonStyle() {
        return "-fx-font-family: " + FONT_STACK + "; -fx-font-size: 10.5px; -fx-font-weight: 900;"
                + " -fx-background-color: #dff4e8; -fx-text-fill: #17623b;"
                + " -fx-border-color: #9fd9b7; -fx-border-radius: 4; -fx-background-radius: 4;";
    }
}
