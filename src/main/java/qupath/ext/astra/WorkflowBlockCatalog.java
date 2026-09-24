package qupath.ext.astra;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Manifest-backed catalog and compatibility rules for workflow-builder tools.
 */
final class WorkflowBlockCatalog {

    private WorkflowBlockCatalog() {
    }

    static List<WorkflowBlock> load(ManifestSet manifests) {
        Objects.requireNonNull(manifests, "manifests");
        return manifests.workflowBuilderTools().stream()
                .map(WorkflowBlockCatalog::fromManifest)
                .toList();
    }

    static WorkflowValidation validate(List<WorkflowBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return WorkflowValidation.invalid("Add at least one tool to the workflow.");
        }
        for (int index = 1; index < blocks.size(); index++) {
            WorkflowBlock previous = blocks.get(index - 1);
            WorkflowBlock current = blocks.get(index);
            if (!previous.outputType().equals(current.inputType())) {
                return WorkflowValidation.invalid(
                        "'" + current.label() + "' requires " + readableType(current.inputType())
                                + ", but '" + previous.label() + "' produces "
                                + readableType(previous.outputType()) + ".");
            }
        }
        return WorkflowValidation.valid(
                blocks.size() == 1
                        ? "1 tool is ready to run."
                        : blocks.size() + " tools are ready to run in order.");
    }

    static List<WorkflowBlock> search(List<WorkflowBlock> blocks, String query) {
        String needle = normalize(query);
        if (needle.isBlank()) {
            return blocks == null ? List.of() : List.copyOf(blocks);
        }
        List<String> terms = List.of(needle.split(" "));
        return blocks == null
                ? List.of()
                : blocks.stream()
                        .filter(block -> terms.stream().allMatch(block.searchText()::contains))
                        .toList();
    }

    private static WorkflowBlock fromManifest(Map<String, Object> raw) {
        return new WorkflowBlock(
                required(raw, "id"),
                required(raw, "runnableId"),
                required(raw, "label"),
                required(raw, "description"),
                required(raw, "category"),
                required(raw, "inputType"),
                required(raw, "outputType"),
                stringList(raw.get("keywords")),
                required(raw, "menuPath"),
                required(raw, "scriptResource"));
    }

    private static String required(Map<String, Object> raw, String key) {
        String value = stringValue(raw.get(key)).trim();
        if (value.isBlank()) {
            throw new IllegalStateException("Workflow builder tool is missing '" + key + "'.");
        }
        return value;
    }

    private static String readableType(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        ArrayList<String> values = new ArrayList<>();
        for (Object item : list) {
            String value = stringValue(item).trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    record WorkflowBlock(
            String id,
            String runnableId,
            String label,
            String description,
            String category,
            String inputType,
            String outputType,
            List<String> keywords,
            String menuPath,
            String scriptResource) {

        WorkflowBlock {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }

        String searchText() {
            return normalize(String.join(" ", List.of(
                    label,
                    description,
                    category,
                    runnableId,
                    menuPath,
                    scriptResource,
                    String.join(" ", keywords))));
        }
    }

    record WorkflowValidation(boolean valid, String message) {

        static WorkflowValidation valid(String message) {
            return new WorkflowValidation(true, message);
        }

        static WorkflowValidation invalid(String message) {
            return new WorkflowValidation(false, message);
        }
    }
}
