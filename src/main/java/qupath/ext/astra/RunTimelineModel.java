package qupath.ext.astra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RunTimelineModel {

    private static final Pattern PIPELINE_ID = Pattern.compile("PIPELINE_ID\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SCRIPT_ACTION = Pattern.compile("(?:SCRIPT_ACTION\\s*=\\s*|[\"']?SCRIPT_ACTION[\"']?\\s*:\\s*)[\"']([A-Z_]+)[\"']");
    private static final Pattern MODES_TO_RUN = Pattern.compile("(?:MODES_TO_RUN\\s*=\\s*|[\"']?MODES_TO_RUN[\"']?\\s*:\\s*)\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");

    private final LinkedHashMap<String, RunTimelineStep> steps = new LinkedHashMap<>();
    private String pipelineId = "";
    private String pipelineName = "";
    private String currentStageId = "RUN_STARTED";
    private int warnings;
    private int errors;
    private RunTimelineOutcome outcome = RunTimelineOutcome.RUNNING;
    private long runStartedNanos;
    private long lastObservedNanos;

    void start(String scriptName, String configuredScript) {
        steps.clear();
        warnings = 0;
        errors = 0;
        outcome = RunTimelineOutcome.RUNNING;
        runStartedNanos = System.nanoTime();
        lastObservedNanos = runStartedNanos;
        pipelineId = extractPipelineId(configuredScript);
        pipelineName = scriptName == null || scriptName.isBlank() ? displayPipelineName(pipelineId) : scriptName;
        addStep("RUN_STARTED", "Run Started", RunTimelineState.ACTIVE);
        currentStageId = "RUN_STARTED";
        seedConfiguredStages(configuredScript);
        addStep("COMPLETE", "Complete", RunTimelineState.PENDING);
    }

    void accept(RunLogEvent event) {
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

    List<RunTimelineStep> steps() {
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
        long end = outcome == RunTimelineOutcome.RUNNING ? System.nanoTime() : Math.max(lastObservedNanos, runStartedNanos);
        return "elapsed " + RunTimelineStep.formatDuration(Math.max(0L, end - runStartedNanos));
    }

    int warningCount() {
        return warnings;
    }

    int errorCount() {
        return errors;
    }

    RunTimelineOutcome outcome() {
        return outcome;
    }

    List<String> labelsForTest() {
        return steps.values().stream().map(RunTimelineStep::label).toList();
    }

    private void seedConfiguredStages(String configuredScript) {
        String action = extractScriptAction(configuredScript);
        if ("EXPORT".equals(action)) {
            addStep("EXPORT", "Export", RunTimelineState.PENDING);
            return;
        }
        if ("RESET_IMAGE".equals(action) || "RESET_PROJECT".equals(action)) {
            addStep(action, action.equals("RESET_IMAGE") ? "Reset Image" : "Reset Project", RunTimelineState.PENDING);
            return;
        }
        List<String> modes = extractModes(configuredScript);
        if (modes.isEmpty()) {
            return;
        }
        for (String mode : modes) {
            addStep(RunLogPresenter.normalizeStageId(mode), displayStage(mode), RunTimelineState.PENDING);
        }
    }

    private void activate(String id, String label) {
        String stageId = blankDefault(id, "MESSAGE");
        completeActiveIfRunning();
        addStep(stageId, blankDefault(label, displayStage(stageId)), RunTimelineState.ACTIVE);
        currentStageId = stageId;
    }

    private void complete(String id, String label) {
        String stageId = blankDefault(id, currentStageId);
        addStep(stageId, blankDefault(label, displayStage(stageId)), RunTimelineState.COMPLETE);
        steps.computeIfPresent(stageId, (key, step) -> step.withState(RunTimelineState.COMPLETE));
        currentStageId = stageId;
    }

    private void completeRun() {
        completeActiveIfRunning();
        steps.computeIfPresent("COMPLETE", (key, step) -> step.withState(RunTimelineState.COMPLETE));
        currentStageId = "COMPLETE";
        outcome = RunTimelineOutcome.COMPLETED;
    }

    private void cancelRun() {
        steps.computeIfPresent(currentStageId, (key, step) -> step.withState(RunTimelineState.CANCELLED));
        steps.computeIfPresent("COMPLETE", (key, step) -> step.withState(RunTimelineState.CANCELLED).withLabel("Cancelled"));
        currentStageId = "COMPLETE";
        outcome = RunTimelineOutcome.CANCELLED;
    }

    private void markWarning(RunLogEvent event) {
        warnings++;
        String id = blankDefault(event.stageId(), currentStageId);
        if (!id.isBlank()) {
            addStep(id, blankDefault(event.stageLabel(), displayStage(id)), RunTimelineState.WARNING);
            steps.computeIfPresent(id, (key, step) -> step.withState(RunTimelineState.WARNING));
            currentStageId = id;
        }
    }

    private void markError(RunLogEvent event) {
        errors++;
        outcome = RunTimelineOutcome.FAILED;
        String id = blankDefault(event.stageId(), currentStageId);
        addStep(id, blankDefault(event.stageLabel(), displayStage(id)), RunTimelineState.ERROR);
        steps.computeIfPresent(id, (key, step) -> step.withState(RunTimelineState.ERROR));
        steps.computeIfPresent("COMPLETE", (key, step) -> step.withState(RunTimelineState.ERROR).withLabel("Failed"));
        currentStageId = id;
    }

    private void completeActiveIfRunning() {
        if (currentStageId == null || currentStageId.isBlank() || "COMPLETE".equals(currentStageId)) {
            return;
        }
        steps.computeIfPresent(currentStageId, (key, step) ->
                step.state() == RunTimelineState.ACTIVE ? step.withState(RunTimelineState.COMPLETE) : step);
    }

    private void addStep(String id, String label, RunTimelineState state) {
        String stageId = blankDefault(id, "MESSAGE");
        steps.putIfAbsent(stageId, new RunTimelineStep(stageId, blankDefault(label, displayStage(stageId)), RunTimelineState.PENDING, Map.of(), 0L, 0L));
        if (state != RunTimelineState.PENDING) {
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
        return RunLogPresenter.titleCase(id.replace('-', ' ').replace('_', ' '));
    }

    private static String displayStage(String id) {
        return RunLogPresenter.titleCase(String.valueOf(id == null ? "" : id).replace('_', ' '));
    }

    private static String imageLabel(RunLogEvent event, String fallback) {
        if (event.imageIndex() != null && event.imageCount() != null) {
            return fallback + " " + event.imageIndex() + "/" + event.imageCount();
        }
        return fallback;
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

record RunTimelineStep(String id, String label, RunTimelineState state, Map<String, String> metrics,
                            long startedNanos, long endedNanos) {
    RunTimelineStep {
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        state = state == null ? RunTimelineState.PENDING : state;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        startedNanos = Math.max(0L, startedNanos);
        endedNanos = Math.max(0L, endedNanos);
    }

    RunTimelineStep withState(RunTimelineState next) {
        long now = System.nanoTime();
        long start = startedNanos;
        long end = endedNanos;
        if (next == RunTimelineState.ACTIVE && start == 0L) {
            start = now;
        }
        if (isTerminal(next)) {
            if (start == 0L) {
                start = now;
            }
            end = now;
        }
        return new RunTimelineStep(id, label, next, metrics, start, end);
    }

    RunTimelineStep withLabel(String nextLabel) {
        return new RunTimelineStep(id, nextLabel, state, metrics, startedNanos, endedNanos);
    }

    RunTimelineStep withMetrics(Map<String, String> nextMetrics) {
        if (nextMetrics == null || nextMetrics.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>(metrics);
        merged.putAll(nextMetrics);
        return new RunTimelineStep(id, label, state, merged, startedNanos, endedNanos);
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

    private static boolean isTerminal(RunTimelineState state) {
        return state == RunTimelineState.COMPLETE
                || state == RunTimelineState.WARNING
                || state == RunTimelineState.ERROR
                || state == RunTimelineState.CANCELLED;
    }
}

enum RunTimelineState {
    PENDING,
    ACTIVE,
    COMPLETE,
    WARNING,
    ERROR,
    CANCELLED
}

enum RunTimelineOutcome {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
