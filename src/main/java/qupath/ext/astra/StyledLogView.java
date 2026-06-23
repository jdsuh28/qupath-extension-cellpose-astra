package qupath.ext.astra;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.Node;
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
    private static final double LOG_STACK_GAP =
            LauncherGeometryTokens.INTRA_PANEL_SUBTLE_GAP - LauncherGeometryTokens.SURFACE_BORDER_WIDTH;
    private static final double LOG_ROW_GAP =
            LauncherGeometryTokens.INTRA_PANEL_SUBTLE_GAP;
    private static final double LOG_TIGHT_GAP =
            LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP + LauncherGeometryTokens.SURFACE_BORDER_WIDTH;
    private static final double LOG_CARD_VERTICAL_INSET =
            LauncherGeometryTokens.INTRA_PANEL_SUBTLE_GAP + LauncherGeometryTokens.SURFACE_BORDER_WIDTH;
    private static final double LOG_CARD_HORIZONTAL_INSET =
            LauncherGeometryTokens.INTRA_PANEL_MARGIN - (LauncherGeometryTokens.SURFACE_BORDER_WIDTH * 2.0);
    private static final double LOG_GROUP_TAB_LEFT_INSET =
            LOG_CARD_VERTICAL_INSET;
    private static final double LOG_GROUP_BODY_GAP =
            LauncherGeometryTokens.INTRA_PANEL_TIGHT_GAP;
    private static final double LOG_LINE_GAP =
            LOG_STACK_GAP;
    private static final double LOG_ACCENT_WIDTH =
            LauncherGeometryTokens.LAYOUT_UNIT / 8.0;
    private static final double LOG_ACCENT_HEIGHT =
            LauncherGeometryTokens.INTRA_PANEL_SUBTLE_GAP * 2.0;
    private static final double LOG_BADGE_MIN_WIDTH =
            LauncherGeometryTokens.LAYOUT_UNIT * 29.0 / 12.0;
    private static final double LOG_KEY_VALUE_WIDTH =
            LauncherGeometryTokens.LAYOUT_UNIT * 59.0 / 12.0;
    private static final double LOG_COMMAND_KEY_WIDTH =
            LauncherGeometryTokens.LAYOUT_UNIT * 11.0 / 2.0;
    private static final double LOG_ADVICE_LABEL_WIDTH =
            LauncherGeometryTokens.LAYOUT_UNIT * 43.0 / 12.0;
    private static final double LOG_DOT_SIZE =
            LauncherGeometryTokens.INTRA_PANEL_SUBTLE_GAP;

    private final VBox entries = new VBox(LOG_STACK_GAP);
    private final StringBuilder plainText = new StringBuilder();
    private final StringBuilder rawText = new StringBuilder();
    private final ScrollPane scroll;
    private final RunTimelineModel timeline = new RunTimelineModel();
    private final RunProgressTracker progressTracker = new RunProgressTracker();
    private final RunLogBlockAccumulator blockAccumulator = new RunLogBlockAccumulator();
    private final Label statusTitle = new Label("Ready");
    private final Label statusDetail = new Label("Waiting for an ASTRA run.");
    private final VBox failureSummary = new VBox(LOG_TIGHT_GAP);
    private final HBox timelineRail = new HBox(LOG_TIGHT_GAP + LauncherGeometryTokens.SURFACE_BORDER_WIDTH);
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
        super(LOG_STACK_GAP);
        setPadding(new Insets(LOG_ROW_GAP));
        addStyleClass(this, "astra-log-view");

        Button copy = new Button("Copy All");
        copy.setFocusTraversable(false);
        styleCopyButton(copy, false);
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(plainText.toString());
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText("Copied");
            styleCopyButton(copy, true);
            PauseTransition reset = new PauseTransition(Duration.seconds(1.2));
            reset.setOnFinished(done -> {
                copy.setText("Copy All");
                styleCopyButton(copy, false);
            });
            reset.play();
        });

        HBox toolbar = new HBox(LOG_ROW_GAP, copy);
        toolbar.setAlignment(Pos.CENTER_RIGHT);

        HBox statusLine = new HBox(LOG_ROW_GAP, statusDetail);
        statusLine.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(statusDetail, Priority.ALWAYS);

        failureSummary.setVisible(false);
        failureSummary.setManaged(false);
        failureSummary.setPadding(logCardPadding());
        addStyleClass(failureSummary, "astra-log-failure-summary");

        VBox statusContent = new VBox(
                LOG_TIGHT_GAP + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                statusTitle,
                statusLine,
                timelineRail);
        statusContent.setPadding(logCardPadding());
        addStyleClass(statusContent, "astra-log-status-card");
        addStyleClass(statusTitle, "astra-log-status-title");
        addStyleClass(statusDetail, "astra-log-status-detail");
        timelineRail.setAlignment(Pos.CENTER_LEFT);

        entries.setFillWidth(true);
        scroll = new ScrollPane(entries);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(520.0);
        addStyleClass(scroll, "astra-log-scroll");
        scroll.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            autoScroll = newValue.doubleValue() >= 0.985d;
        });
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(statusContent, failureSummary, toolbar, scroll);
    }

    private static Insets logCardPadding() {
        return new Insets(
                LOG_CARD_VERTICAL_INSET,
                LOG_CARD_HORIZONTAL_INSET,
                LOG_CARD_HORIZONTAL_INSET,
                LOG_CARD_HORIZONTAL_INSET);
    }

    private static Insets logCompactCardPadding() {
        return new Insets(
                LOG_STACK_GAP,
                LOG_CARD_VERTICAL_INSET,
                LOG_STACK_GAP,
                LOG_CARD_VERTICAL_INSET);
    }

    private static Insets logEmphasisCardPadding() {
        return new Insets(
                LOG_CARD_HORIZONTAL_INSET,
                LOG_CARD_HORIZONTAL_INSET + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                LOG_CARD_HORIZONTAL_INSET + LauncherGeometryTokens.SURFACE_BORDER_WIDTH,
                LOG_CARD_HORIZONTAL_INSET + LauncherGeometryTokens.SURFACE_BORDER_WIDTH);
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

    void refreshTimelineElapsed() {
        renderTimeline();
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
        VBox wrapper = new VBox(LauncherGeometryTokens.FLUSH);
        wrapper.setFillWidth(true);

        Label tab = new Label(source.displayName());
        addStyleClass(tab, "astra-log-source-tab");
        addStyleClass(tab, "astra-log-source-" + cssToken(source.name()));
        VBox.setMargin(tab, new Insets(
                LauncherGeometryTokens.FLUSH,
                LauncherGeometryTokens.FLUSH,
                LauncherGeometryTokens.FLUSH,
                LOG_GROUP_TAB_LEFT_INSET));

        currentGroupBody = new VBox(LOG_GROUP_BODY_GAP);
        currentGroupBody.setPadding(logCardPadding());
        addStyleClass(currentGroupBody, "astra-log-source-block");
        addStyleClass(currentGroupBody, "astra-log-source-" + cssToken(source.name()));
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
        currentHiddenBody = new VBox(LOG_GROUP_BODY_GAP);
        currentHiddenBody.setVisible(false);
        currentHiddenBody.setManaged(false);
        currentHiddenToggle = new Button("Show Cellpose details");
        currentHiddenToggle.setFocusTraversable(false);
        addStyleClass(currentHiddenToggle, "astra-button");
        addStyleClass(currentHiddenToggle, "astra-log-disclosure-button");
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
        HBox row = new HBox(LOG_LINE_GAP);
        row.setAlignment(Pos.TOP_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);

        Region accent = new Region();
        accent.setMinWidth(LOG_ACCENT_WIDTH);
        accent.setPrefWidth(LOG_ACCENT_WIDTH);
        accent.setMaxWidth(LOG_ACCENT_WIDTH);
        accent.setMinHeight(LOG_ACCENT_HEIGHT);
        addStyleClass(accent, "astra-log-line-accent");
        addStyleClass(accent, "astra-log-severity-" + cssToken(entry.severity().name()));

        Label text = new Label(RunLogPresenter.shortDisplayText(entry.text()));
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        addStyleClass(text, "astra-log-line-text");
        addStyleClass(text, "astra-log-severity-" + cssToken(entry.severity().name()));
        if (entry.kind() == RunLogKind.SEPARATOR) {
            addStyleClass(text, "astra-log-line-separator");
        }
        HBox.setHgrow(text, Priority.ALWAYS);

        if (showsSeverityBadge(entry.severity())) {
            Label badge = new Label(entry.severity().displayName());
            badge.setMinWidth(LOG_BADGE_MIN_WIDTH);
            badge.setAlignment(Pos.CENTER);
            addStyleClass(badge, "astra-log-severity-badge");
            addStyleClass(badge, "astra-log-severity-" + cssToken(entry.severity().name()));
            row.getChildren().addAll(accent, badge, text);
        } else {
            row.getChildren().addAll(accent, text);
        }
        return row;
    }

    private VBox createStageCard(RunLogEntry entry, RunLogEvent event) {
        VBox card = new VBox(LOG_TIGHT_GAP + LauncherGeometryTokens.SURFACE_BORDER_WIDTH);
        card.setPadding(logCardPadding());
        addStyleClass(card, "astra-log-message-card");
        addStyleClass(card, "astra-log-severity-" + cssToken(entry.severity().name()));
        Label title = new Label(RunLogPresenter.shortDisplayText(entry.text()));
        title.setWrapText(true);
        addStyleClass(title, "astra-log-card-title");
        card.getChildren().add(title);
        Map<String, String> badges = event == null ? Map.of() : event.metrics();
        HBox badgeRow = badgeRow(badges);
        if (!badgeRow.getChildren().isEmpty()) {
            card.getChildren().add(badgeRow);
        }
        return card;
    }

    private VBox createKeyValueCard(RunLogEntry entry) {
        VBox card = new VBox(LOG_TIGHT_GAP);
        card.setPadding(logCompactCardPadding());
        addStyleClass(card, "astra-log-key-value-card");
        RunLogMetrics.keyValue(entry).ifPresent(kv -> {
            HBox row = new HBox(LOG_ROW_GAP);
            row.setAlignment(Pos.BASELINE_LEFT);
            Label key = new Label(kv.key());
            key.setMinWidth(LOG_KEY_VALUE_WIDTH);
            addStyleClass(key, "astra-log-key");
            Label value = new Label(RunLogPresenter.shortDisplayText(kv.value()));
            value.setWrapText(true);
            addStyleClass(value, "astra-log-value");
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
        VBox card = new VBox(LOG_TIGHT_GAP + LauncherGeometryTokens.SURFACE_BORDER_WIDTH);
        card.setPadding(logEmphasisCardPadding());
        addStyleClass(card, "astra-log-message-card");
        addStyleClass(card, "astra-log-severity-" + cssToken(block.severity().name()));
        Label title = new Label(RunLogPresenter.shortDisplayText(block.title()));
        title.setWrapText(true);
        addStyleClass(title, "astra-log-card-title");
        card.getChildren().add(title);
        if (!block.subtitle().isBlank()) {
            Label subtitle = new Label(RunLogPresenter.shortDisplayText(block.subtitle()));
            subtitle.setWrapText(true);
            addStyleClass(subtitle, "astra-log-card-subtitle");
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
        HBox row = new HBox(LOG_ROW_GAP);
        row.setAlignment(Pos.BASELINE_LEFT);
        Label key = new Label(kv.key());
        key.setMinWidth(LOG_COMMAND_KEY_WIDTH);
        addStyleClass(key, "astra-log-key");
        Label value = new Label(RunLogPresenter.shortDisplayText(kv.value()));
        value.setWrapText(true);
        addStyleClass(value, "astra-log-value");
        HBox.setHgrow(value, Priority.ALWAYS);
        row.getChildren().addAll(key, value);
        return row;
    }

    private VBox createCommandBlock(RunLogEntry entry) {
        VBox card = new VBox(LOG_TIGHT_GAP);
        card.setPadding(logCardPadding());
        addStyleClass(card, "astra-log-command-card");
        Label title = new Label("Command");
        addStyleClass(title, "astra-log-command-title");
        Label command = new Label(RunLogPresenter.shortDisplayText(entry.text()));
        command.setWrapText(true);
        addStyleClass(command, "astra-log-command-text");
        card.getChildren().addAll(title, command);
        return card;
    }

    private HBox badgeRow(Map<String, String> badges) {
        HBox row = new HBox(LOG_TIGHT_GAP);
        row.setAlignment(Pos.CENTER_LEFT);
        if (badges == null) {
            return row;
        }
        badges.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                Label badge = new Label(key + " " + RunLogPresenter.shortDisplayText(value));
                addStyleClass(badge, "astra-log-metric-badge");
                row.getChildren().add(badge);
            }
        });
        return row;
    }

    private void renderTimeline() {
        statusTitle.setText(timeline.statusTitle());
        statusDetail.setText(timeline.statusDetail(progressTracker.detail()));
        timelineRail.getChildren().clear();
        for (RunTimelineStep step : timeline.steps()) {
            if (step.state() == RunTimelineState.WARNING) {
                continue;
            }
            timelineRail.getChildren().add(timelineNode(step));
        }
    }

    private HBox timelineNode(RunTimelineStep step) {
        HBox node = new HBox(LOG_GROUP_BODY_GAP);
        node.setAlignment(Pos.CENTER_LEFT);
        Region dot = new Region();
        dot.setMinSize(LOG_DOT_SIZE, LOG_DOT_SIZE);
        dot.setPrefSize(LOG_DOT_SIZE, LOG_DOT_SIZE);
        dot.setMaxSize(LOG_DOT_SIZE, LOG_DOT_SIZE);
        addStyleClass(dot, "astra-log-timeline-dot");
        addStyleClass(dot, "astra-log-timeline-" + cssToken(step.state().name()));
        Label label = new Label(step.label());
        addStyleClass(label, "astra-log-timeline-label");
        addStyleClass(label, "astra-log-timeline-" + cssToken(step.state().name()));
        node.getChildren().addAll(dot, label);
        if (!step.durationLabel().isBlank() && step.state() != RunTimelineState.PENDING) {
            Label duration = new Label(step.durationLabel());
            addStyleClass(duration, "astra-log-timeline-duration");
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
        addStyleClass(title, "astra-log-failure-title");
        Label message = new Label(RunLogPresenter.shortDisplayText(advice.message()));
        message.setWrapText(true);
        addStyleClass(message, "astra-log-failure-message");
        failureSummary.getChildren().addAll(title, message);
        failureSummary.getChildren().add(createAdviceRow("Likely cause", advice.likelyCause()));
        failureSummary.getChildren().add(createAdviceRow("Next action", advice.nextAction()));
        String context = advice.source().displayName() + (advice.stage().isBlank() ? "" : " | " + advice.stage());
        failureSummary.getChildren().add(createAdviceRow("Source", context));
        failureSummary.setVisible(true);
        failureSummary.setManaged(true);
    }

    private HBox createAdviceRow(String labelText, String valueText) {
        HBox row = new HBox(LOG_ROW_GAP);
        row.setAlignment(Pos.BASELINE_LEFT);
        Label label = new Label(labelText);
        label.setMinWidth(LOG_ADVICE_LABEL_WIDTH);
        addStyleClass(label, "astra-log-advice-label");
        Label value = new Label(RunLogPresenter.shortDisplayText(valueText));
        value.setWrapText(true);
        addStyleClass(value, "astra-log-advice-value");
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

    private static void styleCopyButton(Button copy, boolean copied) {
        copy.getStyleClass().removeIf(name -> name.startsWith("astra-log-copy-button"));
        addStyleClass(copy, "astra-button");
        addStyleClass(copy, "astra-log-copy-button");
        if (copied) {
            addStyleClass(copy, "astra-log-copy-button-copied");
        }
    }

    private static void addStyleClass(Node node, String styleClass) {
        if (node != null && styleClass != null && !styleClass.isBlank()
                && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        }
    }

    private static String cssToken(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }
}
