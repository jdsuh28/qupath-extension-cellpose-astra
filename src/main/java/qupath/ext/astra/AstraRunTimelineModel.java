package qupath.ext.astra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AstraRunTimelineModel {

    private static final Pattern PIPELINE_ID = Pattern.compile("PIPELINE_ID\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SCRIPT_ACTION = Pattern.compile("(?:SCRIPT_ACTION\\s*=\\s*|[\"']?SCRIPT_ACTION[\"']?\\s*:\\s*)[\"']([A-Z_]+)[\"']");
    private static final Pattern MODES_TO_RUN = Pattern.compile("(?:MODES_TO_RUN\\s*=\\s*|[\"']?MODES_TO_RUN[\"']?\\s*:\\s*)\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    private final LinkedHashMap<String, AstraRunTimelineStep> steps = new LinkedHashMap<>();
    private String pipelineId = "";
    private String pipelineName = "";
    private String currentStageId = "RUN_STARTED";
    private int warnings;
    private int errors;
    private AstraRunTimelineOutcome outcome = AstraRunTimelineOutcome.RUNNING;
    private long runStartedNanos;
    private long lastObservedNanos;

    void start(String scriptName, String configuredScript) {
        steps.clear();
        warnings = 0;
        errors = 0;
        outcome = AstraRunTimelineOutcome.RUNNING;
        runStartedNanos = System.nanoTime();
        lastObservedNanos = runStartedNanos;
        pipelineId = extractPipelineId(configuredScript);
        pipelineName = scriptName == null || scriptName.isBlank() ? displayPipelineName(pipelineId) : scriptName;
        addStep("RUN_STARTED", "Run Started", AstraRunTimelineState.ACTIVE);
        currentStageId = "RUN_STARTED";
        seedConfiguredStages(configuredScript);
        addStep("COMPLETE", "Complete", AstraRunTimelineState.PENDING);
    }

    void accept(AstraRunLogEvent event) {
        if (event == null) {
            return;
        }
        lastObservedNanos = System.nanoTime();
        switch (event.type()) {
            case START -> activate("RUN_STARTED", "Run Started");
            case PREFLIGHT -> activate("PREFLIGHT", "Preflight");
            case SCOPE -> activate("SCOPE", imageLabel(event, "Scope"));
            case STAGE_START -> activate(event.stageId(), event.stageLabel());
            case STAGE_COMPLETE -> complete(event.stageId(), event.stageLabel());
            case EXTERNAL_RUNTIME -> activate("EXTERNAL_RUNTIME", "External Runtime");
            case SAVE -> complete("SAVE", imageLabel(event, "Save"));
            case EXPORT -> complete("EXPORT", "Export");
            case RESET -> complete(blankDefault(event.stageId(), "RESET"), blankDefault(event.stageLabel(), "Reset"));
            case WARNING -> markWarning(event);
            case ERROR -> markError(event);
            case COMPLETE -> completeRun();
            case CANCELLED -> cancelRun();
            case MESSAGE -> {
                if (!event.metrics().isEmpty() && !currentStageId.isBlank()) {
                    steps.computeIfPresent(currentStageId, (id, step) -> step.withMetrics(event.metrics()));
                }
            }
        }
        if (!event.metrics().isEmpty() && !currentStageId.isBlank()) {
            steps.computeIfPresent(currentStageId, (id, step) -> step.withMetrics(event.metrics()));
        }
    }

    List<AstraRunTimelineStep> steps() {
        return List.copyOf(steps.values());
    }

    String statusTitle() {
        return switch (outcome) {
            case RUNNING -> "Running " + (pipelineName.isBlank() ? "ASTRA" : pipelineName);
            case COMPLETED -> "Run Completed";
            case FAILED -> "Run Failed";
            case CANCELLED -> "Run Cancelled";
        };
    }

    String statusDetail() {
        return statusDetail("");
    }

    String statusDetail(String liveDetail) {
        String active = currentStageId.isBlank() || !steps.containsKey(currentStageId) ? "Starting" : steps.get(currentStageId).label();
        String detail = liveDetail == null || liveDetail.isBlank() ? active : active + " | " + liveDetail;
        String elapsed = elapsedLabel();
        String elapsedText = elapsed.isBlank() ? "" : " | " + elapsed;
        String counts = (warnings > 0 || errors > 0) ? " | warnings " + warnings + " | errors " + errors : "";
        return detail + elapsedText + counts;
    }

    String elapsedLabel() {
        if (runStartedNanos <= 0L) {
            return "";
        }
        long end = outcome == AstraRunTimelineOutcome.RUNNING ? System.nanoTime() : Math.max(lastObservedNanos, runStartedNanos);
        return "elapsed " + AstraRunTimelineStep.formatDuration(Math.max(0L, end - runStartedNanos));
    }

    int warningCount() {
        return warnings;
    }

    int errorCount() {
        return errors;
    }

    AstraRunTimelineOutcome outcome() {
        return outcome;
    }

    List<String> labelsForTest() {
        return steps.values().stream().map(AstraRunTimelineStep::label).toList();
    }

    private void seedConfiguredStages(String configuredScript) {
        String action = extractScriptAction(configuredScript);
        if ("EXPORT".equals(action)) {
            addStep("EXPORT", "Export", AstraRunTimelineState.PENDING);
            return;
        }
        if ("RESET_IMAGE".equals(action) || "RESET_PROJECT".equals(action)) {
            addStep(action, action.equals("RESET_IMAGE") ? "Reset Image" : "Reset Project", AstraRunTimelineState.PENDING);
            return;
        }
        List<String> modes = extractModes(configuredScript);
        if (modes.isEmpty()) {
            return;
        }
        for (String mode : modes) {
            addStep(AstraRunLogPresenter.normalizeStageId(mode), displayStage(mode), AstraRunTimelineState.PENDING);
        }
    }

    private void activate(String id, String label) {
        String stageId = blankDefault(id, "MESSAGE");
        completeActiveIfRunning();
        addStep(stageId, blankDefault(label, displayStage(stageId)), AstraRunTimelineState.ACTIVE);
        currentStageId = stageId;
    }

    private void complete(String id, String label) {
        String stageId = blankDefault(id, currentStageId);
        addStep(stageId, blankDefault(label, displayStage(stageId)), AstraRunTimelineState.COMPLETE);
        steps.computeIfPresent(stageId, (key, step) -> step.withState(AstraRunTimelineState.COMPLETE));
        currentStageId = stageId;
    }

    private void completeRun() {
        completeActiveIfRunning();
        steps.computeIfPresent("COMPLETE", (key, step) -> step.withState(AstraRunTimelineState.COMPLETE));
        currentStageId = "COMPLETE";
        outcome = AstraRunTimelineOutcome.COMPLETED;
    }

    private void cancelRun() {
        steps.computeIfPresent(currentStageId, (key, step) -> step.withState(AstraRunTimelineState.CANCELLED));
        steps.computeIfPresent("COMPLETE", (key, step) -> step.withState(AstraRunTimelineState.CANCELLED).withLabel("Cancelled"));
        currentStageId = "COMPLETE";
        outcome = AstraRunTimelineOutcome.CANCELLED;
    }

    private void markWarning(AstraRunLogEvent event) {
        warnings++;
        String id = blankDefault(event.stageId(), currentStageId);
        if (!id.isBlank()) {
            addStep(id, blankDefault(event.stageLabel(), displayStage(id)), AstraRunTimelineState.WARNING);
            steps.computeIfPresent(id, (key, step) -> step.withState(AstraRunTimelineState.WARNING));
            currentStageId = id;
        }
    }

    private void markError(AstraRunLogEvent event) {
        errors++;
        outcome = AstraRunTimelineOutcome.FAILED;
        String id = blankDefault(event.stageId(), currentStageId);
        addStep(id, blankDefault(event.stageLabel(), displayStage(id)), AstraRunTimelineState.ERROR);
        steps.computeIfPresent(id, (key, step) -> step.withState(AstraRunTimelineState.ERROR));
        steps.computeIfPresent("COMPLETE", (key, step) -> step.withState(AstraRunTimelineState.ERROR).withLabel("Failed"));
        currentStageId = id;
    }

    private void completeActiveIfRunning() {
        if (currentStageId == null || currentStageId.isBlank() || "COMPLETE".equals(currentStageId)) {
            return;
        }
        steps.computeIfPresent(currentStageId, (key, step) ->
                step.state() == AstraRunTimelineState.ACTIVE ? step.withState(AstraRunTimelineState.COMPLETE) : step);
    }

    private void addStep(String id, String label, AstraRunTimelineState state) {
        String stageId = blankDefault(id, "MESSAGE");
        steps.putIfAbsent(stageId, new AstraRunTimelineStep(stageId, blankDefault(label, displayStage(stageId)), AstraRunTimelineState.PENDING, Map.of(), 0L, 0L));
        if (state != AstraRunTimelineState.PENDING) {
            steps.computeIfPresent(stageId, (key, step) -> step.withState(state));
        }
    }

    private static String extractPipelineId(String script) {
        Matcher matcher = PIPELINE_ID.matcher(String.valueOf(script == null ? "" : script));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String extractScriptAction(String script) {
        Matcher matcher = SCRIPT_ACTION.matcher(String.valueOf(script == null ? "" : script));
        return matcher.find() ? matcher.group(1).trim().toUpperCase(Locale.ROOT) : "RUN";
    }

    static List<String> extractModes(String script) {
        Matcher matcher = MODES_TO_RUN.matcher(String.valueOf(script == null ? "" : script));
        if (!matcher.find()) {
            return List.of();
        }
        List<String> modes = new ArrayList<>();
        Matcher quoted = QUOTED.matcher(matcher.group(1));
        while (quoted.find()) {
            modes.add(quoted.group(1).trim().toUpperCase(Locale.ROOT));
        }
        return modes;
    }

    private static String displayPipelineName(String id) {
        if (id == null || id.isBlank()) {
            return "ASTRA";
        }
        return AstraRunLogPresenter.titleCase(id.replace('-', ' ').replace('_', ' '));
    }

    private static String displayStage(String id) {
        return AstraRunLogPresenter.titleCase(String.valueOf(id == null ? "" : id).replace('_', ' '));
    }

    private static String imageLabel(AstraRunLogEvent event, String fallback) {
        if (event.imageIndex() != null && event.imageCount() != null) {
            return fallback + " " + event.imageIndex() + "/" + event.imageCount();
        }
        return fallback;
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

record AstraRunTimelineStep(String id, String label, AstraRunTimelineState state, Map<String, String> metrics,
                            long startedNanos, long endedNanos) {
    AstraRunTimelineStep {
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        state = state == null ? AstraRunTimelineState.PENDING : state;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        startedNanos = Math.max(0L, startedNanos);
        endedNanos = Math.max(0L, endedNanos);
    }

    AstraRunTimelineStep withState(AstraRunTimelineState next) {
        long now = System.nanoTime();
        long start = startedNanos;
        long end = endedNanos;
        if (next == AstraRunTimelineState.ACTIVE && start == 0L) {
            start = now;
        }
        if (isTerminal(next)) {
            if (start == 0L) {
                start = now;
            }
            end = now;
        }
        return new AstraRunTimelineStep(id, label, next, metrics, start, end);
    }

    AstraRunTimelineStep withLabel(String nextLabel) {
        return new AstraRunTimelineStep(id, nextLabel, state, metrics, startedNanos, endedNanos);
    }

    AstraRunTimelineStep withMetrics(Map<String, String> nextMetrics) {
        if (nextMetrics == null || nextMetrics.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(metrics);
        merged.putAll(nextMetrics);
        return new AstraRunTimelineStep(id, label, state, merged, startedNanos, endedNanos);
    }

    String durationLabel() {
        if (startedNanos <= 0L) {
            return "";
        }
        long end = endedNanos > startedNanos ? endedNanos : System.nanoTime();
        return formatDuration(Math.max(0L, end - startedNanos));
    }

    static String formatDuration(long nanos) {
        long seconds = Math.max(0L, nanos / 1_000_000_000L);
        long minutes = seconds / 60L;
        long remaining = seconds % 60L;
        if (minutes > 0L) {
            return minutes + "m " + String.format(Locale.ROOT, "%02ds", remaining);
        }
        return remaining + "s";
    }

    private static boolean isTerminal(AstraRunTimelineState state) {
        return state == AstraRunTimelineState.COMPLETE
                || state == AstraRunTimelineState.WARNING
                || state == AstraRunTimelineState.ERROR
                || state == AstraRunTimelineState.CANCELLED;
    }
}

enum AstraRunTimelineState {
    PENDING,
    ACTIVE,
    COMPLETE,
    WARNING,
    ERROR,
    CANCELLED
}

enum AstraRunTimelineOutcome {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
