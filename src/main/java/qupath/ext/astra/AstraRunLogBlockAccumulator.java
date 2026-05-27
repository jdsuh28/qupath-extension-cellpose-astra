package qupath.ext.astra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class AstraRunLogBlockAccumulator {

    private final List<AstraRunLogEntry> entries = new ArrayList<>();
    private boolean open;

    Optional<AstraRunLogRenderedBlock> accept(AstraRunLogEntry entry) {
        if (entry == null) {
            return flush();
        }
        if (entry.kind() == AstraRunLogKind.SEPARATOR) {
            if (!open) {
                if (entry.source() != AstraRunLogSource.ASTRA) {
                    return Optional.empty();
                }
                open = true;
                entries.clear();
                entries.add(entry);
                return Optional.empty();
            }
            entries.add(entry);
            Optional<AstraRunLogRenderedBlock> rendered = flush();
            open = false;
            return rendered;
        }
        if (!open) {
            return Optional.empty();
        }
        entries.add(entry);
        return Optional.empty();
    }

    Optional<AstraRunLogRenderedBlock> flush() {
        if (!open || entries.stream().noneMatch(e -> e.kind() != AstraRunLogKind.SEPARATOR)) {
            entries.clear();
            open = false;
            return Optional.empty();
        }
        AstraRunLogRenderedBlock rendered = render(entries);
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

    static AstraRunLogRenderedBlock render(List<AstraRunLogEntry> blockEntries) {
        List<AstraRunLogEntry> meaningful = blockEntries == null ? List.of() : blockEntries.stream()
                .filter(e -> e != null && e.kind() != AstraRunLogKind.SEPARATOR && !e.text().isBlank())
                .toList();
        String title = meaningful.isEmpty() ? "ASTRA" : meaningful.get(0).text();
        String subtitle = "";
        List<AstraRunLogKeyValue> kvs = new ArrayList<>();
        Map<String, String> metrics = new LinkedHashMap<>();
        AstraRunLogSeverity severity = AstraRunLogSeverity.NEUTRAL;
        for (int i = 0; i < meaningful.size(); i++) {
            AstraRunLogEntry entry = meaningful.get(i);
            if (entry.severity() == AstraRunLogSeverity.ERROR) {
                severity = AstraRunLogSeverity.ERROR;
            } else if (entry.severity() == AstraRunLogSeverity.WARNING && severity != AstraRunLogSeverity.ERROR) {
                severity = AstraRunLogSeverity.WARNING;
            } else if (entry.severity() == AstraRunLogSeverity.SUCCESS && severity == AstraRunLogSeverity.NEUTRAL) {
                severity = AstraRunLogSeverity.SUCCESS;
            }
            metrics.putAll(AstraRunLogMetrics.badges(entry));
            AstraRunLogMetrics.keyValue(entry).ifPresent(kvs::add);
        }
        for (int i = 1; i < meaningful.size(); i++) {
            AstraRunLogEntry entry = meaningful.get(i);
            if (AstraRunLogMetrics.keyValue(entry).isEmpty()) {
                subtitle = entry.text();
                break;
            }
        }
        return new AstraRunLogRenderedBlock(title, subtitle, severity, kvs, metrics, meaningful);
    }
}
