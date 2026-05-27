package qupath.ext.astra;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AstraRunLogMetrics {

    private static final Pattern KEY_VALUE = Pattern.compile("^([A-Za-z][A-Za-z0-9 ._()/-]{1,42})\\s*:\\s*(.+)$");
    private static final Pattern IMAGE_PROGRESS = Pattern.compile("^Image (?:start|saved)\\s*:\\s*\\[(\\d+)/(\\d+)]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELLS = Pattern.compile("\\b(?:Cells quantified|Cells created|cells|nuclei)\\s*[:=]\\s*(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGIONS = Pattern.compile("\\bRegions?\\s*[:=]\\s*(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGES = Pattern.compile("\\bImage entries\\s*[:=]\\s*(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUNTIME = Pattern.compile("\\bcompleted in\\s+([0-9.]+\\s*sec)\\b", Pattern.CASE_INSENSITIVE);

    private AstraRunLogMetrics() {
        throw new AssertionError("No instances");
    }

    static Optional<AstraRunLogKeyValue> keyValue(AstraRunLogEntry entry) {
        if (entry == null) {
            return Optional.empty();
        }
        Matcher matcher = KEY_VALUE.matcher(entry.text());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new AstraRunLogKeyValue(matcher.group(1), matcher.group(2)));
    }

    static List<AstraRunLogKeyValue> keyValues(AstraRunLogEntry entry) {
        return keyValue(entry).map(List::of).orElse(List.of());
    }

    static Map<String, String> badges(AstraRunLogEntry entry) {
        if (entry == null || entry.text().isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        String text = entry.text();
        keyValue(entry).ifPresent(kv -> {
            String key = kv.key().toLowerCase();
            if (key.contains("target")) out.put("Target", kv.value());
            else if (key.contains("mode")) out.put("Mode", kv.value());
            else if (key.contains("regions")) out.put("Regions", kv.value());
            else if (key.contains("image entries")) out.put("Images", kv.value());
            else if (key.contains("cells")) out.put("Cells", kv.value());
            else if (key.contains("exports")) out.put("Exports", kv.value());
            else if (key.contains("gpu")) out.put("GPU", kv.value());
        });
        addFirst(out, "Cells", CELLS.matcher(text));
        addFirst(out, "Regions", REGIONS.matcher(text));
        addFirst(out, "Images", IMAGES.matcher(text));
        addFirst(out, "Runtime", RUNTIME.matcher(text));
        return out;
    }

    static Optional<int[]> imageProgress(AstraRunLogEntry entry) {
        if (entry == null) {
            return Optional.empty();
        }
        Matcher matcher = IMAGE_PROGRESS.matcher(entry.text());
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))});
    }

    private static void addFirst(Map<String, String> out, String key, Matcher matcher) {
        if (!out.containsKey(key) && matcher.find()) {
            out.put(key, matcher.group(1));
        }
    }
}
