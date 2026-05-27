package qupath.ext.astra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RunProgressTracker {

    private static final Pattern CELLPOSE_PERCENT = Pattern.compile("^(\\d{1,3})%\\|");
    private static final Pattern CELLPOSE_INDEX = Pattern.compile("\\|\\s*(\\d+)/(\\d+)\\s*\\[");
    private static final Pattern REGION_PROGRESS = Pattern.compile("\\b(?:Region|ROI)\\s+(?:detected|start|saved)?\\s*:?\\s*\\[(\\d+)/(\\d+)]", Pattern.CASE_INSENSITIVE);

    private Integer imageIndex;
    private Integer imageCount;
    private Integer regionIndex;
    private Integer regionCount;
    private String cellposeProgress = "";
    private String cells = "";
    private String regions = "";

    void reset() {
        imageIndex = null;
        imageCount = null;
        regionIndex = null;
        regionCount = null;
        cellposeProgress = "";
        cells = "";
        regions = "";
    }

    void accept(RunLogEvent event) {
        if (event == null) {
            return;
        }
        if (event.imageIndex() != null && event.imageCount() != null) {
            imageIndex = event.imageIndex();
            imageCount = event.imageCount();
        }
        updateMetrics(event.metrics());
        updateEntry(event.entry());
    }

    String detail() {
        List<String> parts = new ArrayList<>();
        if (imageIndex != null && imageCount != null) {
            parts.add("Image " + imageIndex + "/" + imageCount);
        }
        if (regionIndex != null && regionCount != null) {
            parts.add("Region " + regionIndex + "/" + regionCount);
        }
        if (!cellposeProgress.isBlank()) {
            parts.add(cellposeProgress);
        }
        if (!cells.isBlank()) {
            parts.add("Cells " + cells);
        }
        if (!regions.isBlank() && regionIndex == null) {
            parts.add("Regions " + regions);
        }
        return String.join(" | ", parts);
    }

    private void updateMetrics(Map<String, String> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        if (metrics.containsKey("Cells")) {
            cells = metrics.get("Cells");
        }
        if (metrics.containsKey("Regions")) {
            regions = metrics.get("Regions");
        }
        if (metrics.containsKey("Images") && imageCount == null) {
            imageCount = parsePositiveInt(metrics.get("Images"));
        }
    }

    private void updateEntry(RunLogEntry entry) {
        if (entry == null) {
            return;
        }
        Matcher region = REGION_PROGRESS.matcher(entry.text());
        if (region.find()) {
            regionIndex = parsePositiveInt(region.group(1));
            regionCount = parsePositiveInt(region.group(2));
        }
        if (entry.source() != RunLogSource.CELLPOSE || entry.kind() != RunLogKind.PROGRESS) {
            return;
        }
        String progress = cellposeProgress(entry.text());
        if (!progress.isBlank()) {
            cellposeProgress = progress;
        }
    }

    static String cellposeProgress(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) {
            return "";
        }
        Matcher percent = CELLPOSE_PERCENT.matcher(value);
        Matcher index = CELLPOSE_INDEX.matcher(value);
        String percentText = percent.find() ? percent.group(1) + "%" : "";
        String indexText = index.find() ? index.group(1) + "/" + index.group(2) : "";
        if (!percentText.isBlank() && !indexText.isBlank()) {
            return "Cellpose " + percentText + " (" + indexText + ")";
        }
        if (!percentText.isBlank()) {
            return "Cellpose " + percentText;
        }
        if (!indexText.isBlank()) {
            return "Cellpose " + indexText;
        }
        return "";
    }

    private static Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(String.valueOf(value == null ? "" : value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
