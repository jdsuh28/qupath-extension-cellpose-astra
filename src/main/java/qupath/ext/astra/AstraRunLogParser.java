package qupath.ext.astra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AstraRunLogParser {

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

    private AstraRunLogParser() {
        throw new AssertionError("No instances");
    }

    static List<AstraRunLogEntry> parse(String text, AstraRunLogSource defaultSource, AstraRunLogSeverity defaultSeverity) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int lineCount = normalized.endsWith("\n") ? lines.length - 1 : lines.length;
        List<AstraRunLogEntry> entries = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            AstraRunLogEntry entry = parseLine(lines[i], defaultSource, defaultSeverity);
            if (entry != null && !entry.text().isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    static String formatCleanText(String text, AstraRunLogSource defaultSource, AstraRunLogSeverity defaultSeverity) {
        List<AstraRunLogEntry> entries = parse(text, defaultSource, defaultSeverity);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (AstraRunLogEntry entry : entries) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            String prefix = entry.severity() == AstraRunLogSeverity.ERROR ? "ERROR: " : "";
            out.append(prefix).append(entry.text());
        }
        if (text.endsWith("\n")) {
            out.append('\n');
        }
        return out.toString();
    }

    private static AstraRunLogEntry parseLine(String rawLine, AstraRunLogSource defaultSource, AstraRunLogSeverity defaultSeverity) {
        String raw = rawLine == null ? "" : rawLine;
        String line = raw.trim();
        if (line.isBlank()) {
            return null;
        }

        AstraRunLogSource source = defaultSource == null ? AstraRunLogSource.SYSTEM : defaultSource;
        AstraRunLogSeverity severity = defaultSeverity == null ? AstraRunLogSeverity.NEUTRAL : defaultSeverity;
        AstraRunLogKind kind = AstraRunLogKind.MESSAGE;

        line = line.replaceFirst("^\\[LOG]\\s*", "");
        if (line.matches("^-{8,}$") || line.matches("^={8,}$")) {
            return new AstraRunLogEntry(AstraRunLogSource.ASTRA, AstraRunLogSeverity.NEUTRAL, AstraRunLogKind.SEPARATOR, line, raw);
        }

        Matcher bracket = BRACKET_SEVERITY.matcher(line);
        if (bracket.matches()) {
            source = AstraRunLogSource.ASTRA;
            severity = AstraRunLogSeverity.fromToken(bracket.group(1), severity);
            line = bracket.group(2).trim();
        }

        Matcher cellposeWithSeverity = CELLPOSE_WITH_SEVERITY.matcher(line);
        if (cellposeWithSeverity.matches()) {
            source = AstraRunLogSource.CELLPOSE;
            severity = AstraRunLogSeverity.fromToken(cellposeWithSeverity.group(1), severity);
            line = cellposeWithSeverity.group(2).trim();
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher cellposeFromQuPath = CELLPOSE_FROM_QUPATH.matcher(line);
        if (cellposeFromQuPath.matches()) {
            source = AstraRunLogSource.CELLPOSE;
            severity = AstraRunLogSeverity.fromToken(cellposeFromQuPath.group(1), severity);
            line = cellposeFromQuPath.group(2).trim();
            return parseCellposePayload(line, severity, raw);
        }

        Matcher cellposeDirect = CELLPOSE_DIRECT.matcher(line);
        if (cellposeDirect.matches() && looksLikeCellposeLine(line)) {
            source = AstraRunLogSource.CELLPOSE;
            line = cellposeDirect.group(1).trim();
            return parseCellposePayload(line, severity, raw);
        }

        Matcher pipelineStage = PIPELINE_STAGE.matcher(collapseRepeatedPipelineNames(line));
        if (pipelineStage.matches()) {
            source = AstraRunLogSource.ASTRA;
            severity = AstraRunLogSeverity.fromToken(pipelineStage.group(1), severity == AstraRunLogSeverity.NEUTRAL ? AstraRunLogSeverity.INFO : severity);
            String stage = titleCaseToken(pipelineStage.group(3).replace('_', ' '));
            String message = pipelineStage.group(4) == null ? "" : pipelineStage.group(4).trim();
            line = stage + (message.isBlank() ? "" : ": " + message);
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher pipelineMessage = PIPELINE_MESSAGE.matcher(collapseRepeatedPipelineNames(line));
        if (pipelineMessage.matches()) {
            source = AstraRunLogSource.ASTRA;
            severity = AstraRunLogSeverity.fromToken(pipelineMessage.group(1), severity == AstraRunLogSeverity.NEUTRAL ? AstraRunLogSeverity.INFO : severity);
            line = pipelineMessage.group(3).trim();
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher qupathClass = QUPATH_CLASS_LOG.matcher(line);
        if (qupathClass.matches()) {
            source = AstraRunLogSource.QUPATH;
            severity = AstraRunLogSeverity.fromToken(qupathClass.group(1), severity);
            line = qupathClass.group(2).trim();
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher prefixedClass = PREFIXED_CLASS_LOG.matcher(line);
        if (prefixedClass.matches()) {
            severity = AstraRunLogSeverity.fromToken(prefixedClass.group(1), severity);
            String className = prefixedClass.group(2).trim();
            line = prefixedClass.group(3).trim();
            source = isAstraStageOrMessage(className) ? AstraRunLogSource.ASTRA : AstraRunLogSource.QUPATH;
            return finalizeEntry(source, severity, kind, line, raw);
        }

        Matcher simpleSeverity = SIMPLE_SEVERITY_PREFIX.matcher(line);
        if (simpleSeverity.matches()) {
            severity = AstraRunLogSeverity.fromToken(simpleSeverity.group(1), severity);
            line = simpleSeverity.group(2).trim();
            source = inferSourceFromMessage(line, source);
            return finalizeEntry(source, severity, kind, line, raw);
        }

        if (isAstraStageOrMessage(line)) {
            source = AstraRunLogSource.ASTRA;
        }

        Matcher astraStagePrefix = ASTRA_STAGE_PREFIX.matcher(line);
        if (astraStagePrefix.matches()) {
            source = AstraRunLogSource.ASTRA;
            line = astraStagePrefix.group(2).trim();
        }

        if (line.toUpperCase(Locale.ROOT).startsWith("ERROR ")) {
            source = defaultSource == null ? AstraRunLogSource.SCRIPT : defaultSource;
            severity = AstraRunLogSeverity.ERROR;
            line = line.replaceFirst("(?i)^ERROR\\s+", "");
        }
        if (line.toLowerCase(Locale.ROOT).startsWith("traceback ")) {
            source = AstraRunLogSource.PYTHON;
            severity = AstraRunLogSeverity.ERROR;
        }
        if (KEY_VALUE.matcher(line).matches()) {
            kind = AstraRunLogKind.KEY_VALUE;
        }
        return finalizeEntry(source, severity, kind, line, raw);
    }

    private static AstraRunLogEntry parseCellposePayload(String line, AstraRunLogSeverity fallbackSeverity, String raw) {
        AstraRunLogSeverity severity = fallbackSeverity == null ? AstraRunLogSeverity.INFO : fallbackSeverity;
        Matcher timestamp = CELLPOSE_TIMESTAMP.matcher(line);
        if (timestamp.matches()) {
            severity = AstraRunLogSeverity.fromToken(timestamp.group(1), severity);
            line = timestamp.group(2).trim();
        }
        line = line.replace(">>>> ", "").trim();
        return finalizeEntry(AstraRunLogSource.CELLPOSE, severity, AstraRunLogKind.MESSAGE, line, raw);
    }

    private static AstraRunLogEntry finalizeEntry(AstraRunLogSource source, AstraRunLogSeverity severity, AstraRunLogKind kind, String line, String raw) {
        String cleaned = line == null ? "" : line.trim();
        if (cleaned.isBlank()) {
            return null;
        }
        if (cleaned.matches("^-{8,}$") || cleaned.matches("^={8,}$") || cleaned.matches("^!{8,}$")) {
            kind = AstraRunLogKind.SEPARATOR;
        }
        if (cleaned.matches("^\\d+%\\|.*") || cleaned.contains("it/s]")) {
            kind = AstraRunLogKind.PROGRESS;
        }
        if (severity == AstraRunLogSeverity.NEUTRAL) {
            severity = inferSeverity(cleaned, severity);
        }
        return new AstraRunLogEntry(source, severity, kind, cleaned, raw);
    }

    private static AstraRunLogSeverity inferSeverity(String line, AstraRunLogSeverity fallback) {
        String upper = line == null ? "" : line.toUpperCase(Locale.ROOT);
        if (upper.contains("[ERROR]") || upper.startsWith("ERROR") || upper.contains(" ERROR ") || upper.contains("TRACEBACK")) {
            return AstraRunLogSeverity.ERROR;
        }
        if (upper.contains("[WARN]") || upper.startsWith("WARN") || upper.contains(" WARNING") || upper.contains(" WARN ")) {
            return AstraRunLogSeverity.WARNING;
        }
        if (upper.contains("[DONE]") || upper.contains("[SUCCESS]") || upper.endsWith(" COMPLETE") || upper.contains(" COMPLETED")) {
            return AstraRunLogSeverity.SUCCESS;
        }
        if (upper.contains("[CANCELLED]") || upper.contains("[CANCELED]")) {
            return AstraRunLogSeverity.CANCELLED;
        }
        return fallback == null ? AstraRunLogSeverity.NEUTRAL : fallback;
    }

    private static boolean looksLikeCellposeLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("cellpose") || lower.startsWith("astracellpose2d");
    }

    private static boolean isAstraStageOrMessage(String token) {
        String upper = token == null ? "" : token.toUpperCase(Locale.ROOT);
        return upper.contains("COLOCALIZATION")
                || upper.contains("VASCULAR")
                || upper.contains("TRAINING")
                || upper.contains("TUNING")
                || upper.contains("VALIDATION")
                || upper.contains("ASTRA")
                || upper.startsWith("QUANTIFY")
                || upper.startsWith("DETECT CELLS")
                || upper.startsWith("IMAGE START")
                || upper.startsWith("IMAGE SAVED")
                || upper.startsWith("PROJECT RUN")
                || upper.startsWith("RUN START")
                || upper.startsWith("RUN END");
    }

    private static AstraRunLogSource inferSourceFromMessage(String line, AstraRunLogSource fallback) {
        String upper = line == null ? "" : line.toUpperCase(Locale.ROOT);
        if (upper.startsWith("ASTRA") || isAstraStageOrMessage(line)) {
            return AstraRunLogSource.ASTRA;
        }
        if (upper.startsWith("CELLPOS") || upper.contains("ASTRACELLPOSE2D")) {
            return AstraRunLogSource.CELLPOSE;
        }
        return fallback == null ? AstraRunLogSource.SYSTEM : fallback;
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
