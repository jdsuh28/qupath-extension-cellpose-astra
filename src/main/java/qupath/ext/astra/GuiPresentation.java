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
final class GuiPresentation {

    private static final ManifestSet MANIFESTS = ManifestSet.load();

    private GuiPresentation() {
        throw new AssertionError("No instances");
    }

    static String displayLabel(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String explicit = MANIFESTS.labels().get(name);
        if (explicit != null) {
            return explicit;
        }
        String[] tokens = name.split("_");
        List<String> words = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String mapped = MANIFESTS.labelTokens().get(token);
            words.add(mapped != null ? mapped : titleCaseToken(token));
        }
        return String.join(" ", words);
    }

    static List<String> visibleRunModeOptions(String pipelineName, List<String> scriptOptions) {
        List<String> contractStages = MANIFESTS.visibleStages(pipelineName);
        if (!contractStages.isEmpty()) {
            return contractStages;
        }
        return scriptOptions == null ? List.of() : List.copyOf(scriptOptions);
    }

    static boolean supportsHeaderExport(String pipelineName) {
        return MANIFESTS.headerActions(pipelineName).contains("EXPORT");
    }

    static boolean supportsAnalysisHeaderActions(String pipelineName) {
        return !MANIFESTS.headerActions(pipelineName).isEmpty();
    }

    static List<String> workflowSequence(String pipelineName) {
        return MANIFESTS.workflowSequence(pipelineName);
    }

    static String workflowActiveLabel(String pipelineName) {
        return MANIFESTS.workflowActiveLabel(pipelineName);
    }

    static String description(String pipelineName) {
        return MANIFESTS.description(pipelineName);
    }

    static boolean advancedControlsLockedByDefault() {
        Object raw = MANIFESTS.advancedControls().get("lockedByDefault");
        return raw instanceof Boolean locked ? locked : true;
    }

    static String advancedUnlockPhrase() {
        Object raw = MANIFESTS.advancedControls().get("unlockPhrase");
        String phrase = raw == null ? "" : String.valueOf(raw).trim();
        return phrase.isBlank() ? "ADVANCED" : phrase;
    }

    static String advancedControlsDescription() {
        Object raw = MANIFESTS.advancedControls().get("description");
        String description = raw == null ? "" : String.valueOf(raw).trim();
        return description.isBlank()
                ? "Reveal the complete developer-oriented pipeline controls."
                : description;
    }

    static String displayOption(String option) {
        if (option == null || option.isBlank()) {
            return "";
        }
        String explicit = MANIFESTS.optionLabels().get(option);
        return explicit != null ? explicit : displayLabel(option);
    }

    static String displayOption(String constantName, String option) {
        if ("IMAGE_SCOPE".equals(constantName)) {
            if ("CURRENT_IMAGE".equals(option)) return "Current Image";
            if ("SELECTED_ANALYSIS_REGION".equals(option)) return "Selected Region";
            if ("PROJECT_IMAGE_SELECTION".equals(option)) return "Selected Images";
        }
        if ("THRESHOLD_SCOPE".equals(constantName)) {
            if ("IMAGE".equals(option)) return "Per Image";
            if ("REGION".equals(option)) return "Per Region";
            if ("SELECTED_IMAGES".equals(option)) return "Selected Images";
        }
        if ("THRESHOLD_POPULATION".equals(constantName)) {
            if ("CELL_MEAN".equals(option)) return "Cell Mean";
            if ("PIXEL_INTENSITY".equals(option)) return "Pixel Intensity";
        }
        if ("POSITIVITY_METHOD".equals(constantName)) {
            if ("MEAN_INTENSITY".equals(option)) return "Mean Intensity";
            if ("PIXEL_POSITIVE_FRACTION".equals(option)) return "Pixel Positive Fraction";
        }
        if ("EXPRESSION_CLASSIFICATION_MODE".equals(constantName)) {
            if ("PIXEL_LEVEL_SCORE".equals(option)) return "Pixel Level Score";
            if ("LEGACY_BINARY".equals(option)) return "Legacy Binary";
        }
        return displayOption(option);
    }

    private static String titleCaseToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
