package qupath.ext.astra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RunLogParser {

    private static final Pattern BRACKET_SEVERITY = Pattern.compile("^\\[(INFO|WARN|WARNING|ERROR|DONE|SUCCESS|CANCELLED|CANCELED|DEBUG|TRACE)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELLPOSE_FROM_QUPATH = Pattern.compile("^(INFO|WARN|WARNING|ERROR|DEBUG|TRACE):\\s*AstraCellpose2D:\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELLPOSE_DIRECT = Pattern.compile("^(?:AstraCellpose2D|Cellpose)\\s*:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELLPOSE_WITH_SEVERITY = Pattern.compile("^Cellpose\\s+(INFO|WARN|WARNING|ERROR|DEBUG|TRACE):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELLPOSE_TIMESTAMP = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2},\\d{3}\\s+\\[(INFO|WARNING|WARN|ERROR|DEBUG|TRACE)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIPELINE_STAGE = Pattern.compile("^(?:(INFO|WARN|WARNING|ERROR|DEBUG|TRACE):?\\s*)?(COLOCALIZATION|VASCULAR|TRAINING|TUNING|VALIDATION|TOOLS?)\\s+\\[([^\\]]+)]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PIPELINE_MESSAGE = Pattern.compile("^(?:(INFO|WARN|WARNING|ERROR|DEBUG|TRACE):?\\s*)?(COLOCALIZATION|VASCULAR|TRAINING|TUNING|VALIDATION|TOOLS?)\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASTRA_STAGE_PREFIX = Pattern.compile("^(Detect cells|Quantify|Export|Reset|Training|Tuning|Validation|Generate regions|Auto select ROIs|Auto build classifiers)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUPATH_CLASS_LOG = Pattern.compile("^(INFO|WARN|WARNING|ERROR|DEBUG|TRACE)\\s+[^-]+-\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIXED_CLASS_LOG = Pattern.compile("^(INFO|WARN|WARNING|ERROR|DEBUG|TRACE):\\s+([^:]+):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIMPLE_SEVERITY_PREFIX = Pattern.compile("^(INFO|WARN|WARNING|ERROR|DEBUG|TRACE):\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_VALUE = Pattern.compile("^[A-Za-z][A-Za-z0-9 ._()/-]{1,42}\\s*:\\s+.+$");

    private RunLogParser() {
        throw new AssertionError("No instances");
    }

    static List<RunLogEntry> parse(String text, RunLogSource defaultSource, RunLogSeverity defaultSeverity) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int lineCount = normalized.endsWith("\n") ? lines.length - 1 : lines.length;
        List<RunLogEntry> entries = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            RunLogEntry entry = parseLine(lines[i], defaultSource, defaultSeverity);
            if (entry != null && !entry.text().isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    static String formatCleanText(String text, RunLogSource defaultSource, RunLogSeverity defaultSeverity) {
        List<RunLogEntry> entries = parse(text, defaultSource, defaultSeverity);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (RunLogEntry entry : entries) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            String prefix = entry.severity() == RunLogSeverity.ERROR ? "ERROR: " : "";
            out.append(prefix).append(entry.text());
        }
        if (text.endsWith("\n")) {
            out.append('\n');
        }
        return out.toString();
    }

    private static RunLogEntry parseLine(String rawLine, RunLogSource defaultSource, RunLogSeverity defaultSeverity) {
        String raw = rawLine == null ? "" : rawLine;
        String line = raw.trim();
        if (line.isBlank()) {
            return null;
        }

        RunLogSource source = defaultSource == null ? RunLogSource.SYSTEM : defaultSource;
        RunLogSeverity severity = defaultSeverity == null ? RunLogSeverity.NEUTRAL : defaultSeverity;
        RunLogKind kind = RunLogKind.MESSAGE;

        line = line.replaceFirst("^\\[LOG]\\s*", "");
        if (line.matches("^-{8,}$") || line.matches("^={8,}$")) {
            return new RunLogEntry(RunLogSource.ASTRA, RunLogSeverity.NEUTRAL, RunLogKind.SEPARATOR, line, raw);
        }

        Matcher bracket = BRACKET_SEVERITY.matcher(line);
        if (bracket.matches()) {
            source = RunLogSource.ASTRA;
            severity = RunLogSeverity.fromToken(bracket.group(1), severity);
            line = bracket.group(2).trim();
        }

        Matcher cellposeWithSeverity = CELLPOSE_WITH_SEVERITY.matcher(line);
        if (cellposeWithSeverity.matches()) {
            source = RunLogSource.CELLPOSE;
            severity = RunLogSeverity.fromToken(cellposeWithSeverity.group(1), severity);
            line = cellposeWithSeverity.group(2).trim();
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher cellposeFromQuPath = CELLPOSE_FROM_QUPATH.matcher(line);
        if (cellposeFromQuPath.matches()) {
            source = RunLogSource.CELLPOSE;
            severity = RunLogSeverity.fromToken(cellposeFromQuPath.group(1), severity);
            line = cellposeFromQuPath.group(2).trim();
            return parseCellposePayload(line, severity, raw);
        }

        Matcher cellposeDirect = CELLPOSE_DIRECT.matcher(line);
        if (cellposeDirect.matches() && looksLikeCellposeLine(line)) {
            source = RunLogSource.CELLPOSE;
            line = cellposeDirect.group(1).trim();
            return parseCellposePayload(line, severity, raw);
        }

        Matcher pipelineStage = PIPELINE_STAGE.matcher(collapseRepeatedPipelineNames(line));
        if (pipelineStage.matches()) {
            source = RunLogSource.ASTRA;
            severity = RunLogSeverity.fromToken(pipelineStage.group(1), severity == RunLogSeverity.NEUTRAL ? RunLogSeverity.INFO : severity);
            String stage = titleCaseToken(pipelineStage.group(3).replace('_', ' '));
            String message = pipelineStage.group(4) == null ? "" : pipelineStage.group(4).trim();
            line = stage + (message.isBlank() ? "" : ": " + message);
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher pipelineMessage = PIPELINE_MESSAGE.matcher(collapseRepeatedPipelineNames(line));
        if (pipelineMessage.matches()) {
            source = RunLogSource.ASTRA;
            severity = RunLogSeverity.fromToken(pipelineMessage.group(1), severity == RunLogSeverity.NEUTRAL ? RunLogSeverity.INFO : severity);
            line = pipelineMessage.group(3).trim();
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher qupathClass = QUPATH_CLASS_LOG.matcher(line);
        if (qupathClass.matches()) {
            source = RunLogSource.QUPATH;
            severity = RunLogSeverity.fromToken(qupathClass.group(1), severity);
            line = qupathClass.group(2).trim();
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher prefixedClass = PREFIXED_CLASS_LOG.matcher(line);
        if (prefixedClass.matches()) {
            severity = RunLogSeverity.fromToken(prefixedClass.group(1), severity);
            String className = prefixedClass.group(2).trim();
            line = prefixedClass.group(3).trim();
            source = isPipelineStageOrMessage(className) ? RunLogSource.ASTRA : RunLogSource.QUPATH;
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher simpleSeverity = SIMPLE_SEVERITY_PREFIX.matcher(line);
        if (simpleSeverity.matches()) {
            severity = RunLogSeverity.fromToken(simpleSeverity.group(1), severity);
            line = simpleSeverity.group(2).trim();
            source = inferSourceFromMessage(line, source);
            return finalizeEntry(source, severity, kind, line, raw);
        }

        if (isPipelineStageOrMessage(line)) {
            source = RunLogSource.ASTRA;
        }

        Matcher pipelineStagePrefix = ASTRA_STAGE_PREFIX.matcher(line);
        if (pipelineStagePrefix.matches()) {
            source = RunLogSource.ASTRA;
            line = pipelineStagePrefix.group(2).trim();
        }

        if (line.toUpperCase(Locale.ROOT).startsWith("ERROR ")) {
            source = defaultSource == null ? RunLogSource.SCRIPT : defaultSource;
            severity = RunLogSeverity.ERROR;
            line = line.replaceFirst("(?i)^ERROR\\s+", "");
        }
        if (line.toLowerCase(Locale.ROOT).startsWith("traceback ")) {
            source = RunLogSource.PYTHON;
            severity = RunLogSeverity.ERROR;
        }
        if (KEY_VALUE.matcher(line).matches()) {
            kind = RunLogKind.KEY_VALUE;
        }
        return finalizeEntry(source, severity, kind, line, raw);
    }

    private static RunLogEntry parseCellposePayload(String line, RunLogSeverity fallbackSeverity, String raw) {
        RunLogSeverity severity = fallbackSeverity == null ? RunLogSeverity.INFO : fallbackSeverity;
        Matcher timestamp = CELLPOSE_TIMESTAMP.matcher(line);
        if (timestamp.matches()) {
            severity = RunLogSeverity.fromToken(timestamp.group(1), severity);
            line = timestamp.group(2).trim();
        }
        line = line.replace(">>>> ", "").trim();
        return finalizeEntry(RunLogSource.CELLPOSE, severity, RunLogKind.MESSAGE, line, raw);
    }

    private static RunLogEntry finalizeEntry(RunLogSource source, RunLogSeverity severity, RunLogKind kind, String line, String raw) {
        String cleaned = line == null ? "" : line.trim();
        if (cleaned.isBlank()) {
            return null;
        }
        if (cleaned.matches("^-{8,}$") || cleaned.matches("^={8,}$") || cleaned.matches("^!{8,}$")) {
            kind = RunLogKind.SEPARATOR;
        }
        if (cleaned.matches("^\\d+%\\|.*") || cleaned.contains("it/s]")) {
            kind = RunLogKind.PROGRESS;
        }
        if (severity == RunLogSeverity.NEUTRAL) {
            severity = inferSeverity(cleaned, severity);
        }
        return new RunLogEntry(source, severity, kind, cleaned, raw);
    }

    private static RunLogSeverity inferSeverity(String line, RunLogSeverity fallback) {
        String upper = line == null ? "" : line.toUpperCase(Locale.ROOT);
        if (upper.contains("[ERROR]") || upper.startsWith("ERROR") || upper.contains(" ERROR ") || upper.contains("TRACEBACK")) {
            return RunLogSeverity.ERROR;
        }
        if (upper.contains("[WARN]") || upper.startsWith("WARN") || upper.contains(" WARNING") || upper.contains(" WARN ")) {
            return RunLogSeverity.WARNING;
        }
        if (upper.contains("[DONE]") || upper.contains("[SUCCESS]") || upper.endsWith(" COMPLETE") || upper.contains(" COMPLETED")) {
            return RunLogSeverity.SUCCESS;
        }
        if (upper.contains("[CANCELLED]") || upper.contains("[CANCELED]")) {
            return RunLogSeverity.CANCELLED;
        }
        return fallback == null ? RunLogSeverity.NEUTRAL : fallback;
    }

    private static boolean looksLikeCellposeLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("cellpose") || lower.startsWith("astracellpose2d");
    }

    private static boolean isPipelineStageOrMessage(String token) {
        String upper = token == null ? "" : token.toUpperCase(Locale.ROOT);
        return upper.contains("COLOCALIZATION")
                || upper.contains("VASCULAR")
                || upper.contains("TRAINING")
                || upper.contains("TUNING")
                || upper.contains("VALIDATION")
                || upper.startsWith("ASTRA ")
                || upper.startsWith("QUANTIFY")
                || upper.startsWith("DETECT CELLS")
                || upper.startsWith("IMAGE START")
                || upper.startsWith("IMAGE SAVED")
                || upper.startsWith("PROJECT RUN")
                || upper.startsWith("RUN START")
                || upper.startsWith("RUN END");
    }

    private static RunLogSource inferSourceFromMessage(String line, RunLogSource fallback) {
        String upper = line == null ? "" : line.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ASTRA") || isPipelineStageOrMessage(line)) {
            return RunLogSource.ASTRA;
        }
        if (upper.startsWith("CELLPOS") || upper.contains("ASTRACELLPOSE2D")) {
            return RunLogSource.CELLPOSE;
        }
        return fallback == null ? RunLogSource.SYSTEM : fallback;
    }

    private static String collapseRepeatedPipelineNames(String line) {
        String out = line;
        for (String pipeline : List.of("COLOCALIZATION", "VASCULAR", "TRAINING", "TUNING", "VALIDATION")) {
            out = out.replaceAll("(?i)\\b" + pipeline + "\\s+" + pipeline + "\\b", pipeline);
        }
        return out;
    }

    private static String titleCaseToken(String token) {
        String[] words = token.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> titled = new ArrayList<>(words.length);
        for (String word : words) {
            if (!word.isBlank()) {
                titled.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
            }
        }
        return String.join(" ", titled);
    }
}
