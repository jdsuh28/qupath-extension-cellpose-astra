package qupath.ext.astra;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RunLogPresenter {

    private static final Pattern STAGE_COMPLETE = Pattern.compile("^([A-Z0-9_ -]+)\\s+COMPLETE\\b.*$");
    private static final Pattern RUN_HEADER = Pattern.compile("^(?:[A-Z0-9_-]+\\s+)?(PROJECT RUN START|PROJECT RUN END|RUN START|RUN END|TARGET SETUP|VALIDATION REPORT.*|TUNING REPORT.*|TRAINING REPORT.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMAND = Pattern.compile("^(?:bash\\s+-c\\s+.*|[\"']?/[^\\s\"']*/python(?:\\s|$).*|python(?:\\d+(?:\\.\\d+)*)?\\s+.*|(?:conda|mamba)\\s+.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STAGE_HINT = Pattern.compile("\\b(PREFLIGHT|CONFIG|AUTO_BUILD_CLASSIFIERS|AUTO_SELECT_ROIS|GENERATE_REGIONS|DETECT_CELLS|QUANTIFY|EXPORT|RESET_IMAGE|RESET_PROJECT|TRAINING|TUNING|VALIDATION|TARGET SETUP|SEARCH|REPORT)\\b", Pattern.CASE_INSENSITIVE);

    private RunLogPresenter() {
        throw new AssertionError("No instances");
    }

    static RunLogEvent eventFor(RunLogEntry entry) {
        if (entry == null) {
            return null;
        }
        String text = entry.text();
        String upper = text.toUpperCase(Locale.ROOT);
        Map<String, String> badges = RunLogMetrics.badges(entry);

        if (entry.severity() == RunLogSeverity.ERROR) {
            return new RunLogEvent(RunLogEventType.ERROR, "", "", stageIdFromText(text), stageLabelFromText(text), null, null, badges, RunLogMetrics.keyValues(entry), entry);
        }
        if (entry.severity() == RunLogSeverity.WARNING) {
            return new RunLogEvent(RunLogEventType.WARNING, "", "", stageIdFromText(text), stageLabelFromText(text), null, null, badges, RunLogMetrics.keyValues(entry), entry);
        }
        if (entry.severity() == RunLogSeverity.CANCELLED) {
            return RunLogEvent.of(RunLogEventType.CANCELLED, "CANCELLED", "Cancelled", entry);
        }
        if (upper.contains("ASTRA RUN STARTED") || upper.startsWith("STARTED ")) {
            return RunLogEvent.of(RunLogEventType.START, "RUN_STARTED", "Run Started", entry);
        }
        if (upper.contains("PREFLIGHT")) {
            return RunLogEvent.of(RunLogEventType.PREFLIGHT, "PREFLIGHT", "Preflight", entry);
        }
        if (upper.contains("IMAGE SAVED")) {
            int[] progress = RunLogMetrics.imageProgress(entry).orElse(null);
            return new RunLogEvent(RunLogEventType.SAVE, "", "", "SAVE", "Save", progress == null ? null : progress[0], progress == null ? null : progress[1], badges, RunLogMetrics.keyValues(entry), entry);
        }
        if (upper.contains("IMAGE START") || upper.contains("PROJECT RUN START")) {
            int[] progress = RunLogMetrics.imageProgress(entry).orElse(null);
            return new RunLogEvent(RunLogEventType.SCOPE, "", "", "SCOPE", "Scope", progress == null ? null : progress[0], progress == null ? null : progress[1], badges, RunLogMetrics.keyValues(entry), entry);
        }
        if (upper.contains("EXPORT COMPLETE") || upper.contains("SCRIPT ACTION") && upper.contains("EXPORT")) {
            return new RunLogEvent(RunLogEventType.EXPORT, "", "", "EXPORT", "Export", null, null, badges, RunLogMetrics.keyValues(entry), entry);
        }
        if (upper.contains("RESET_IMAGE") || upper.contains("RESET PROJECT") || upper.contains("RESET_IMAGE") || upper.contains("RESET_PROJECT")) {
            return new RunLogEvent(RunLogEventType.RESET, "", "", stageIdFromText(text), stageLabelFromText(text), null, null, badges, RunLogMetrics.keyValues(entry), entry);
        }
        Matcher complete = STAGE_COMPLETE.matcher(upper);
        if (complete.matches()) {
            String label = titleCase(complete.group(1).trim().replace('_', ' '));
            return new RunLogEvent(RunLogEventType.STAGE_COMPLETE, "", "", normalizeStageId(label), label, null, null, badges, RunLogMetrics.keyValues(entry), entry);
        }
        if (upper.contains("RUN END") || entry.severity() == RunLogSeverity.SUCCESS || upper.contains("RUN COMPLETED")) {
            return RunLogEvent.of(RunLogEventType.COMPLETE, "COMPLETE", "Complete", entry);
        }
        if (entry.source() == RunLogSource.CELLPOSE || upper.contains("EXECUTING COMMAND") || isCommand(entry)) {
            return new RunLogEvent(RunLogEventType.EXTERNAL_RUNTIME, "", "", "EXTERNAL_RUNTIME", "External Runtime", null, null, badges, RunLogMetrics.keyValues(entry), entry);
        }
        if (isStageHeader(entry)) {
            return new RunLogEvent(RunLogEventType.STAGE_START, "", "", stageIdFromText(text), stageLabelFromText(text), null, null, badges, RunLogMetrics.keyValues(entry), entry);
        }
        return new RunLogEvent(RunLogEventType.MESSAGE, "", "", stageIdFromText(text), stageLabelFromText(text), null, null, badges, RunLogMetrics.keyValues(entry), entry);
    }

    static boolean isStageCard(RunLogEntry entry) {
        if (entry == null || entry.source() != RunLogSource.ASTRA) {
            return false;
        }
        String upper = entry.text().toUpperCase(Locale.ROOT);
        return RUN_HEADER.matcher(entry.text()).matches()
                || upper.endsWith(" COMPLETE")
                || upper.contains("PROJECT RUN START")
                || upper.contains("PROJECT RUN END")
                || upper.contains("RUN START")
                || upper.contains("RUN END")
                || upper.equals("TARGET SETUP");
    }

    static boolean isCommand(RunLogEntry entry) {
        return entry != null && COMMAND.matcher(entry.text()).matches();
    }

    static boolean isNoisyCellposeDetail(RunLogEntry entry) {
        if (entry == null || entry.source() != RunLogSource.CELLPOSE) {
            return false;
        }
        String upper = entry.text().toUpperCase(Locale.ROOT);
        return entry.kind() == RunLogKind.PROGRESS
                || upper.contains("PROCESSING IMAGE")
                || upper.contains("PROCESSING GRAYSCALE")
                || upper.contains("0%|")
                || upper.contains("100%|");
    }

    static String shortDisplayText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(/Users/[^\\s\"']{16,})([^/\\s\"']+/[^/\\s\"']+/[^/\\s\"']+)$", ".../$2");
    }

    private static boolean isStageHeader(RunLogEntry entry) {
        return entry != null
                && entry.source() == RunLogSource.ASTRA
                && STAGE_HINT.matcher(entry.text()).find();
    }

    private static String stageIdFromText(String text) {
        String upper = text == null ? "" : text.toUpperCase(Locale.ROOT);
        Matcher hint = STAGE_HINT.matcher(upper);
        if (hint.find()) {
            return normalizeStageId(hint.group(1));
        }
        return "";
    }

    private static String stageLabelFromText(String text) {
        String id = stageIdFromText(text);
        if (id.isBlank()) {
            return "";
        }
        return titleCase(id.replace('_', ' '));
    }

    static String normalizeStageId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    static String titleCase(String value) {
        String[] words = String.valueOf(value == null ? "" : value).toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return out.toString();
    }
}
