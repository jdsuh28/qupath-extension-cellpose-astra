package qupath.ext.astra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RunLogBlockAccumulator {

    private final List<RunLogEntry> entries = new ArrayList<>();
    private boolean open;

    Optional<RunLogRenderedBlock> accept(RunLogEntry entry) {
        if (entry == null) {
            return flush();
        }
        if (entry.kind() == RunLogKind.SEPARATOR) {
            if (!open) {
                if (entry.source() != RunLogSource.ASTRA) {
                    return Optional.empty();
                }
                open = true;
                entries.clear();
                entries.add(entry);
                return Optional.empty();
            }
            entries.add(entry);
            Optional<RunLogRenderedBlock> rendered = flush();
            open = false;
            return rendered;
        }
        if (!open) {
            return Optional.empty();
        }
        entries.add(entry);
        return Optional.empty();
    }

    Optional<RunLogRenderedBlock> flush() {
        if (!open || entries.stream().noneMatch(e -> e.kind() != RunLogKind.SEPARATOR)) {
            entries.clear();
            open = false;
            return Optional.empty();
        }
        RunLogRenderedBlock rendered = render(entries);
        entries.clear();
        open = false;
        return Optional.of(rendered);
    }

    boolean isCapturing() {
        return open;
    }

    void reset() {
        entries.clear();
        open = false;
    }

    static RunLogRenderedBlock render(List<RunLogEntry> blockEntries) {
        List<RunLogEntry> meaningful = blockEntries == null ? List.of() : blockEntries.stream()
                .filter(e -> e != null && e.kind() != RunLogKind.SEPARATOR && !e.text().isBlank())
                .toList();
        String title = meaningful.isEmpty() ? "ASTRA" : meaningful.get(0).text();
        String subtitle = "";
        List<RunLogKeyValue> kvs = new ArrayList<>();
        Map<String, String> metrics = new LinkedHashMap<>();
        RunLogSeverity severity = RunLogSeverity.NEUTRAL;
        for (int i = 0; i < meaningful.size(); i++) {
            RunLogEntry entry = meaningful.get(i);
            if (entry.severity() == RunLogSeverity.ERROR) {
                severity = RunLogSeverity.ERROR;
            } else if (entry.severity() == RunLogSeverity.WARNING && severity != RunLogSeverity.ERROR) {
                severity = RunLogSeverity.WARNING;
            } else if (entry.severity() == RunLogSeverity.SUCCESS && severity == RunLogSeverity.NEUTRAL) {
                severity = RunLogSeverity.SUCCESS;
            }
            metrics.putAll(RunLogMetrics.badges(entry));
            RunLogMetrics.keyValue(entry).ifPresent(kvs::add);
        }
        for (int i = 1; i < meaningful.size(); i++) {
            RunLogEntry entry = meaningful.get(i);
            if (RunLogMetrics.keyValue(entry).isEmpty()) {
                subtitle = entry.text();
                break;
            }
        }
        return new RunLogRenderedBlock(title, subtitle, severity, kvs, metrics, meaningful);
    }
}
