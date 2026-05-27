package qupath.ext.astra;

import java.util.ArrayList;
import java.util.List;

final class RunLogGrouper {

    private RunLogGrouper() {
        throw new AssertionError("No instances");
    }

    static List<RunLogBlock> groupBySource(List<RunLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<RunLogBlock> blocks = new ArrayList<>();
        RunLogSource currentSource = null;
        List<RunLogEntry> currentEntries = new ArrayList<>();
        for (RunLogEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (currentSource == null || entry.source() != currentSource) {
                if (!currentEntries.isEmpty()) {
                    blocks.add(new RunLogBlock(currentSource, List.copyOf(currentEntries)));
                    currentEntries.clear();
                }
                currentSource = entry.source();
            }
            currentEntries.add(entry);
        }
        if (!currentEntries.isEmpty()) {
            blocks.add(new RunLogBlock(currentSource, List.copyOf(currentEntries)));
        }
        return blocks;
    }
}

record RunLogBlock(RunLogSource source, List<RunLogEntry> entries) {
}
