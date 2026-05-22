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
            Map.entry("NUC_MIN_MASK_EQUIVALENT_DIAMETER_UM", "Nucleus Minimum Mask Equivalent Diameter"),
            Map.entry("CELL_MIN_MASK_EQUIVALENT_DIAMETER_UM", "Cell Minimum Mask Equivalent Diameter"),
            Map.entry("DETECTION_TARGET", "Detection Target"),
            Map.entry("THRESHOLD_MODE", "Threshold Mode"),
            Map.entry("THRESHOLD_SCOPE", "Threshold Scope"),
            Map.entry("THRESHOLD_SELECTED_IMAGE_NAMES", "Threshold Source Images"),
            Map.entry("MATCH_THRESHOLD_IMAGE_NAMES_AGAINST_ORIGINAL", "Match Threshold Image Names Against Original"),
            Map.entry("MATCH_SELECTED_IMAGE_NAMES_AGAINST_ORIGINAL", "Match Selected Image Names Against Original"),
            Map.entry("BACKGROUND_MODE", "Background Mode"),
            Map.entry("LOCAL_BACKGROUND_PERCENTILE", "Local Background Percentile"),
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
            Map.entry("IMAGE_SCOPE", "Image Scope"),
            Map.entry("SELECTED_IMAGE_NAMES", "Selected Image Names"),
            Map.entry("CLASS_ANALYSIS_REGION", "Analysis Region Class"),
            Map.entry("CLASS_ROI", "ROI Class")
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
        if (supportsAnalysisHeaderActions(pipelineName)) {
            return (scriptOptions == null ? List.<String>of() : scriptOptions).stream()
                    .filter(option -> !"RESET".equals(option))
                    .filter(option -> !"RESET_IMAGE".equals(option))
                    .filter(option -> !"RESET_PROJECT".equals(option))
                    .filter(option -> !"EXPORT".equals(option))
                    .toList();
        }
        return scriptOptions == null ? List.of() : List.copyOf(scriptOptions);
    }

    static boolean supportsHeaderExport(String pipelineName) {
        return supportsAnalysisHeaderActions(pipelineName);
    }

    static boolean supportsAnalysisHeaderActions(String pipelineName) {
        if (pipelineName == null) return false;
        String name = pipelineName.toLowerCase(Locale.ROOT);
        return name.contains("colocalization") || name.contains("vascular");
    }

    static String displayOption(String option) {
        if (option == null || option.isBlank()) {
            return "";
        }
        return switch (option) {
            case "CURRENT_IMAGE" -> "Current Image";
            case "SELECTED_ANALYSIS_REGION" -> "Selected Analysis Region";
            case "PROJECT_IMAGE_SELECTION" -> "Project Image Selection";
            case "SELECTED_IMAGES" -> "Selected Images";
            case "LOCAL_PERCENTILE" -> "Local Percentile";
            case "MANUAL_OFFSET" -> "Manual Offset";
            case "LOG_GAUSSIAN_MIXTURE" -> "Log Gaussian Mixture";
            case "GAUSSIAN_MIXTURE" -> "Gaussian Mixture";
            case "KDE_VALLEY" -> "KDE Valley";
            case "AUTO_OTSU_PER_CHANNEL" -> "Auto Otsu Per Channel";
            case "RANGE_PERCENT" -> "Range Percent";
            case "MANUAL" -> "Manual";
            case "NONE" -> "None";
            case "NUCLEUS" -> "Nucleus";
            case "CELL" -> "Cell";
            case "BOTH" -> "Both";
            case "DETECT_CELLS" -> "Detect Cells";
            case "GENERATE_REGIONS" -> "Generate Regions";
            case "QUANTIFY" -> "Quantify";
            case "AUTO_BUILD_CLASSIFIERS" -> "Auto Build Classifiers";
            case "AUTO_SELECT_ROIS" -> "Auto Select ROIs";
            default -> displayLabel(option);
        };
    }

    private static String titleCaseToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
