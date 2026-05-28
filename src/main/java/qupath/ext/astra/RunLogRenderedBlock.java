package qupath.ext.astra;

import java.util.List;
import java.util.Map;

record RunLogRenderedBlock(
        String title,
        String subtitle,
        RunLogSeverity severity,
        List<RunLogKeyValue> keyValues,
        Map<String, String> metrics,
        List<RunLogEntry> entries
) {
    RunLogRenderedBlock {
        title = title == null ? "" : title.trim();
        subtitle = subtitle == null ? "" : subtitle.trim();
        severity = severity == null ? RunLogSeverity.NEUTRAL : severity;
        keyValues = keyValues == null ? List.of() : List.copyOf(keyValues);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
