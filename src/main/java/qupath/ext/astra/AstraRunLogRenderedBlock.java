package qupath.ext.astra;

import java.util.List;
import java.util.Map;

record AstraRunLogRenderedBlock(
        String title,
        String subtitle,
        AstraRunLogSeverity severity,
        List<AstraRunLogKeyValue> keyValues,
        Map<String, String> metrics,
        List<AstraRunLogEntry> entries
) {
    AstraRunLogRenderedBlock {
        title = title == null ? "" : title.trim();
        subtitle = subtitle == null ? "" : subtitle.trim();
        severity = severity == null ? AstraRunLogSeverity.NEUTRAL : severity;
        keyValues = keyValues == null ? List.of() : List.copyOf(keyValues);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
