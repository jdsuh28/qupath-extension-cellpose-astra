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

    private static final MasterContract CONTRACT = MasterContract.load();

    private AstraGuiPresentation() {
        throw new AssertionError("No instances");
    }

    static String displayLabel(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String explicit = CONTRACT.labels().get(name);
        if (explicit != null) {
            return explicit;
        }
        String[] tokens = name.split("_");
        List<String> words = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String mapped = CONTRACT.labelTokens().get(token);
            words.add(mapped != null ? mapped : titleCaseToken(token));
        }
        return String.join(" ", words);
    }

    static List<String> visibleRunModeOptions(String pipelineName, List<String> scriptOptions) {
        List<String> contractStages = CONTRACT.visibleStages(pipelineName);
        if (!contractStages.isEmpty()) {
            return contractStages;
        }
        return scriptOptions == null ? List.of() : List.copyOf(scriptOptions);
    }

    static boolean supportsHeaderExport(String pipelineName) {
        return CONTRACT.headerActions(pipelineName).contains("EXPORT");
    }

    static boolean supportsAnalysisHeaderActions(String pipelineName) {
        return !CONTRACT.headerActions(pipelineName).isEmpty();
    }

    static String displayOption(String option) {
        if (option == null || option.isBlank()) {
            return "";
        }
        String explicit = CONTRACT.optionLabels().get(option);
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
        return displayOption(option);
    }

    private static String titleCaseToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
