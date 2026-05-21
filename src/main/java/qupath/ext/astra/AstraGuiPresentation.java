package qupath.ext.astra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Central presentation contract between ASTRA script constants and the JavaFX
 * launcher.  Scientific behavior belongs in the base Groovy scripts; this
 * adapter owns user-facing labels, option visibility, and GUI-only routing.
 */
final class AstraGuiPresentation {

    private static final Map<String, String> EXPLICIT_LABELS = Map.ofEntries(
            Map.entry("NUC_MODEL_SOURCE", "Nucleus Model Source"),
            Map.entry("NUC_MODEL_NAME", "Nucleus Model Name"),
            Map.entry("NUC_MODEL_FILE", "Nucleus Model File"),
            Map.entry("NUC_SAVED_MODEL_ID", "Nucleus Saved Model ID"),
            Map.entry("CELL_MODEL_SOURCE", "Cell Model Source"),
            Map.entry("CELL_MODEL_NAME", "Cell Model Name"),
            Map.entry("CELL_MODEL_FILE", "Cell Model File"),
            Map.entry("CELL_SAVED_MODEL_ID", "Cell Saved Model ID"),
            Map.entry("THRESHOLD_SCOPE", "Threshold Scope"),
            Map.entry("BACKGROUND_SCOPE", "Background Scope"),
            Map.entry("BACKGROUND_SUBTRACTION_BY_CHANNEL", "Manual Background Offsets"),
            Map.entry("MANUAL_INTENSITY_THRESHOLDS", "Manual Intensity Thresholds"),
            Map.entry("RANGE_THRESHOLD_FRACTION_BY_MARKER", "Range Threshold Fractions"),
            Map.entry("THRESHOLD_PROVENANCE_BY_MARKER", "Threshold Provenance"),
            Map.entry("COLOCALIZATION_CHECKS", "Colocalization Checks"),
            Map.entry("MODES_TO_RUN", "Stages To Run"),
            Map.entry("USE_GPU", "Use GPU"),
            Map.entry("USE_BATCH_MODE", "Use Batch Mode"),
            Map.entry("USE_PIXEL_SCALING", "Use Pixel Scaling"),
            Map.entry("QC_FOLDER", "QC Folder"),
            Map.entry("QC_FILENAME", "QC Filename"),
            Map.entry("RESULTS_FOLDER", "Results Folder"),
            Map.entry("RESULTS_BASENAME", "Results Basename"),
            Map.entry("SELECTED_IMAGE_NAMES", "Selected Image Names")
    );

    private static final Map<String, String> LABEL_TOKENS = Map.ofEntries(
            Map.entry("NUC", "Nucleus"),
            Map.entry("ROI", "Region"),
            Map.entry("ID", "ID"),
            Map.entry("GPU", "GPU"),
            Map.entry("QC", "QC"),
            Map.entry("CSV", "CSV"),
            Map.entry("DAPI", "DAPI"),
            Map.entry("AF488", "AF488"),
            Map.entry("AF555", "AF555"),
            Map.entry("AF647", "AF647")
    );

    private AstraGuiPresentation() {
        throw new AssertionError("No instances");
    }

    static String displayLabel(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String explicit = EXPLICIT_LABELS.get(name);
        if (explicit != null) {
            return explicit;
        }
        String[] tokens = name.split("_");
        List<String> words = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String mapped = LABEL_TOKENS.get(token);
            words.add(mapped != null ? mapped : titleCaseToken(token));
        }
        return String.join(" ", words);
    }

    static List<String> visibleRunModeOptions(String pipelineName, List<String> scriptOptions) {
        if (pipelineName != null && pipelineName.toLowerCase(Locale.ROOT).contains("colocalization")) {
            return List.of("RESET", "DETECT_CELLS", "QUANTIFY");
        }
        return scriptOptions == null ? List.of() : List.copyOf(scriptOptions);
    }

    static boolean supportsHeaderExport(String pipelineName) {
        return pipelineName != null && pipelineName.toLowerCase(Locale.ROOT).contains("colocalization");
    }

    private static String titleCaseToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
