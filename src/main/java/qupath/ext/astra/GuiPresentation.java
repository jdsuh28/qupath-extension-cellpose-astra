package qupath.ext.astra;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Central presentation contract between script constants and the JavaFX
 * launcher.  Scientific behavior belongs in the base Groovy scripts; this
 * adapter owns user-facing labels, option display, and GUI-only routing.
 */
final class GuiPresentation {

    private static final ManifestSet MANIFESTS = ManifestSet.load();
    private static final List<StandardGroup> STANDARD_GROUPS = loadStandardGroups();
    private static final List<DashboardCard> DASHBOARD_CARDS = loadDashboardCards();
    private static final ParameterModeConfig PARAMETER_MODES = loadParameterModes();

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

    static String extensionReleaseTag() {
        return MANIFESTS.extensionReleaseTag();
    }

    static String defaultParameterMode() {
        return PARAMETER_MODES.defaultMode();
    }

    static String parameterMode(String group, boolean advanced) {
        if (PARAMETER_MODES.advancedOnlyGroups().contains(group)) {
            return "ADVANCED";
        }
        return advanced ? "STANDARD" : "BASIC";
    }

    static List<DashboardCard> dashboardCards() {
        return DASHBOARD_CARDS;
    }

    static List<StandardGroup> standardGroups() {
        return STANDARD_GROUPS;
    }

    static StandardGroup standardGroup(String name) {
        if (name == null || name.isBlank()) {
            return StandardGroup.fallback("");
        }
        return STANDARD_GROUPS.stream()
                .filter(group -> group.name().equals(name))
                .findFirst()
                .orElse(StandardGroup.fallback(name));
    }

    static Optional<StandardGroup> knownStandardGroup(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return STANDARD_GROUPS.stream()
                .filter(group -> group.name().equals(name))
                .findFirst();
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
            if ("ANY_PIXEL_ABOVE_THRESHOLD".equals(option)) return "Any Pixel Above Threshold";
        }
        if ("EXPRESSION_CLASSIFICATION_MODE".equals(constantName)) {
            if ("PIXEL_LEVEL_SCORE".equals(option)) return "Pixel-Level Score";
            if ("LEGACY_BINARY".equals(option)) return "Legacy Binary";
        }
        return displayOption(option);
    }

    private static String titleCaseToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }

    private static List<StandardGroup> loadStandardGroups() {
        List<StandardGroup> groups = MANIFESTS.standardGroups().stream()
                .map(StandardGroup::fromManifest)
                .filter(group -> !group.name().isBlank())
                .sorted(Comparator.comparingInt(StandardGroup::order))
                .toList();
        return groups.isEmpty() ? fallbackStandardGroups() : groups;
    }

    private static List<DashboardCard> loadDashboardCards() {
        List<DashboardCard> cards = MANIFESTS.dashboardCards().stream()
                .map(DashboardCard::fromManifest)
                .filter(card -> !card.name().isBlank() && !card.groups().isEmpty())
                .sorted(Comparator.comparingInt(DashboardCard::order))
                .toList();
        return cards.isEmpty() ? fallbackDashboardCards() : cards;
    }

    private static ParameterModeConfig loadParameterModes() {
        Map<String, Object> map = MANIFESTS.parameterModes();
        String defaultMode = StandardGroup.text(map.get("default"));
        Set<String> advancedOnlyGroups = map.get("advancedOnlyGroups") instanceof List<?> values
                ? values.stream().map(StandardGroup::text).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.of();
        return new ParameterModeConfig(defaultMode.isBlank() ? "BASIC" : defaultMode,
                advancedOnlyGroups);
    }

    private static List<DashboardCard> fallbackDashboardCards() {
        return List.of(
                new DashboardCard("workflow", "Workflow", "Choose what to run and how to stage it.", 1, "Essential", "teal", List.of("Run Setup")),
                new DashboardCard("images-regions", "Images & Regions", "Choose images, regions, and QuPath classes.", 2, "Essential", "bluegray", List.of("Images & Scope", "Classes & Regions")),
                new DashboardCard("channels-markers", "Channels & Markers", "Map biological stains to image channels.", 3, "Essential", "teal", List.of("Channels & Markers")),
                new DashboardCard("models", "Models", "Choose model sources and saved assets.", 4, "Routine", "bluegray", List.of("Models")),
                new DashboardCard("segmentation", "Segmentation", "Control object detection and mask recovery.", 5, "Routine", "teal", List.of("Segmentation")),
                new DashboardCard("classification", "Classification", "Define biological identities and inclusion rules.", 6, "Essential", "sage", List.of("Biological Classification")),
                new DashboardCard("thresholds", "Thresholds & Background", "Resolve positivity and background correction.", 7, "Routine", "amber", List.of("Thresholds & Background")),
                new DashboardCard("runtime-diagnostics", "Runtime & Diagnostics", "Tune execution and inspect troubleshooting controls.", 8, "Optional", "gold", List.of("Runtime & Performance", "Diagnostics", "Developer Overrides", "Advanced")),
                new DashboardCard("output", "Output & Export", "Choose result writing and export behavior.", 9, "Routine", "bluegray", List.of("Output & Export"))
        );
    }

    private static List<StandardGroup> fallbackStandardGroups() {
        return List.of(
                new StandardGroup("Run Setup", "Run Setup",
                        "Choose the workflow path before processing data.",
                        1, "Essential", "teal"),
                new StandardGroup("Images & Scope", "Images & Scope",
                        "Choose which images or regions are in play.",
                        2, "Essential", "bluegray"),
                new StandardGroup("Classes & Regions", "Classes & Regions",
                        "Map QuPath annotations and output classes to the analysis.",
                        3, "Essential", "sage"),
                new StandardGroup("Channels & Markers", "Channels & Markers",
                        "Match biological stains to image channels.",
                        4, "Essential", "teal"),
                new StandardGroup("Models", "Models",
                        "Choose saved models and project-backed model files.",
                        5, "Routine", "bluegray"),
                new StandardGroup("Segmentation", "Segmentation",
                        "Control Cellpose-SAM object recovery.",
                        6, "Routine", "teal"),
                new StandardGroup("Biological Classification",
                        "Biological Classification",
                        "Set anatomy-aware cell identity and inclusion rules.",
                        7, "Essential", "sage"),
                new StandardGroup("Thresholds & Background",
                        "Thresholds & Background",
                        "Control marker scoring and local background correction.",
                        8, "Routine", "amber"),
                new StandardGroup("Runtime & Performance",
                        "Runtime & Performance",
                        "Tune GPU, batching, scaling, and speed.",
                        9, "Optional", "bluegray"),
                new StandardGroup("Output & Export", "Output & Export",
                        "Choose result writing and export behavior.",
                        10, "Routine", "bluegray"),
                new StandardGroup("Diagnostics", "Diagnostics",
                        "Turn on extra checks and troubleshooting output.",
                        11, "Diagnostic", "amber"),
                new StandardGroup("Developer Overrides", "Developer Overrides",
                        "Inspect low-level controls after deliberate unlock.",
                        12, "Advanced", "gold")
        );
    }

    record StandardGroup(String name, String label, String description, int order,
                         String importance, String accentTheme) {

        static StandardGroup fromManifest(Map<String, Object> map) {
            String name = text(map.get("name"));
            String label = text(map.get("label"));
            String description = text(map.get("description"));
            String importance = text(map.get("importance"));
            String accentTheme = text(map.get("accentTheme"));
            int order = number(map.get("order"), Integer.MAX_VALUE);
            return new StandardGroup(
                    name,
                    label.isBlank() ? name : label,
                    description.isBlank() ? "Review related settings." : description,
                    order,
                    importance.isBlank() ? "Routine" : importance,
                    accentTheme.isBlank() ? "bluegray" : accentTheme
            );
        }

        static StandardGroup fallback(String name) {
            return new StandardGroup(
                    name,
                    name == null || name.isBlank() ? "Settings" : name,
                    "Review related settings.",
                    Integer.MAX_VALUE,
                    "Routine",
                    "bluegray"
            );
        }

        private static String text(Object raw) {
            return raw == null ? "" : String.valueOf(raw).trim();
        }

        private static int number(Object raw, int fallback) {
            if (raw instanceof Number number) {
                return number.intValue();
            }
            try {
                return Integer.parseInt(text(raw));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    record DashboardCard(String name, String label, String description, int order,
                         String importance, String accentTheme, List<String> groups) {

        static DashboardCard fromManifest(Map<String, Object> map) {
            String name = StandardGroup.text(map.get("name"));
            String label = StandardGroup.text(map.get("label"));
            String description = StandardGroup.text(map.get("description"));
            String importance = StandardGroup.text(map.get("importance"));
            String accentTheme = StandardGroup.text(map.get("accentTheme"));
            int order = StandardGroup.number(map.get("order"), Integer.MAX_VALUE);
            List<String> groups = map.get("groups") instanceof List<?> values
                    ? values.stream().map(StandardGroup::text).filter(value -> !value.isBlank()).toList()
                    : List.of();
            return new DashboardCard(
                    name,
                    label.isBlank() ? name : label,
                    description.isBlank() ? "Review related settings." : description,
                    order,
                    importance.isBlank() ? "Routine" : importance,
                    accentTheme.isBlank() ? "bluegray" : accentTheme,
                    List.copyOf(groups)
            );
        }
    }

    private record ParameterModeConfig(String defaultMode, Set<String> advancedOnlyGroups) {
    }
}
