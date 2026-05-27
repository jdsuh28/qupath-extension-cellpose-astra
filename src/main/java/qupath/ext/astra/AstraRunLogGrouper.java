package qupath.ext.astra;

import java.util.ArrayList;
import java.util.List;

final class AstraRunLogGrouper {

    private AstraRunLogGrouper() {
        throw new AssertionError("No instances");
    }

    static List<AstraRunLogBlock> groupBySource(List<AstraRunLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<AstraRunLogBlock> blocks = new ArrayList<>();
        AstraRunLogSource currentSource = null;
        List<AstraRunLogEntry> currentEntries = new ArrayList<>();
        for (AstraRunLogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (currentSource == null || entry.source() != currentSource) {
                if (!currentEntries.isEmpty()) {
                    blocks.add(new AstraRunLogBlock(currentSource, List.copyOf(currentEntries)));
                    currentEntries.clear();
                }
                currentSource = entry.source();
            }
            currentEntries.add(entry);
        }
        if (!currentEntries.isEmpty()) {
            blocks.add(new AstraRunLogBlock(currentSource, List.copyOf(currentEntries)));
        }
        return blocks;
    }
}

record AstraRunLogBlock(AstraRunLogSource source, List<AstraRunLogEntry> entries) {
}
