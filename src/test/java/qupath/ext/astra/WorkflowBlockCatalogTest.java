package qupath.ext.astra;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowBlockCatalogTest {

    private static final Path TEST_MANIFEST_ROOT =
            Path.of("src/test/resources/astra/rulebook/manifests");

    @Test
    void catalogContainsOnlyManifestEnabledTools() {
        ManifestSet manifests = ManifestSet.load(TEST_MANIFEST_ROOT);
        @SuppressWarnings("unchecked")
        Map<String, Object> moduleManifest =
                (Map<String, Object>) manifests.root().get("modules");
        @SuppressWarnings("unchecked")
        List<String> runnableOrder =
                (List<String>) moduleManifest.get("runnableOrder");
        assertEquals(List.of(
                        "generate-regions",
                        "count-nuclei",
                        "count-cells",
                        "summarize-objects",
                        "save-current-image"),
                runnableOrder.subList(runnableOrder.size() - 5, runnableOrder.size()));

        List<WorkflowBlockCatalog.WorkflowBlock> blocks =
                WorkflowBlockCatalog.load(manifests);

        assertEquals(List.of(
                        "generate-regions",
                        "count-nuclei",
                        "count-cells",
                        "summarize-objects",
                        "save-current-image"),
                blocks.stream().map(WorkflowBlockCatalog.WorkflowBlock::runnableId).toList());
        assertTrue(blocks.stream().allMatch(block -> block.id().startsWith("tool-")));
        assertFalse(blocks.stream().anyMatch(block ->
                List.of("training", "tuning", "validation", "vascular", "colocalization")
                        .contains(block.runnableId())));
    }

    @Test
    void searchUsesLabelsDescriptionsCategoriesAndKeywords() {
        List<WorkflowBlockCatalog.WorkflowBlock> blocks =
                WorkflowBlockCatalog.load(ManifestSet.load(TEST_MANIFEST_ROOT));

        assertEquals(1, WorkflowBlockCatalog.search(blocks, "region").size());
        assertEquals(1, WorkflowBlockCatalog.search(blocks, "roi").size());
        assertEquals(1, WorkflowBlockCatalog.search(blocks, "training annotations").size());
        assertTrue(WorkflowBlockCatalog.search(blocks, "count cells").stream()
                .anyMatch(block -> block.runnableId().equals("count-cells")));
        assertEquals(
                List.of("count-nuclei"),
                WorkflowBlockCatalog.search(blocks, "nucleus").stream()
                        .map(WorkflowBlockCatalog.WorkflowBlock::runnableId)
                        .toList());
        assertEquals(1, WorkflowBlockCatalog.search(blocks, "save project").size());
    }

    @Test
    void linearValidationRequiresCompatibleTypedPorts() {
        WorkflowBlockCatalog.WorkflowBlock producer = block(
                "producer", "PROJECT_CONTEXT", "CELL_OBJECTS");
        WorkflowBlockCatalog.WorkflowBlock consumer = block(
                "consumer", "CELL_OBJECTS", "MEASUREMENTS");
        WorkflowBlockCatalog.WorkflowBlock incompatible = block(
                "incompatible", "REGIONS", "MEASUREMENTS");

        assertFalse(WorkflowBlockCatalog.validate(List.of()).valid());
        assertTrue(WorkflowBlockCatalog.validate(List.of(producer)).valid());
        assertTrue(WorkflowBlockCatalog.validate(List.of(producer, consumer)).valid());
        assertFalse(WorkflowBlockCatalog.validate(List.of(producer, incompatible)).valid());
    }

    @Test
    void workflowBuilderCssMirrorsGeometryTokensWithoutUnsupportedSizeLookups()
            throws IOException {
        String css = Files.readString(
                Path.of("src/main/resources/qupath/ext/astra/launcher.css"));
        String builderCss = css.substring(css.indexOf("/* Workflow Builder */"));

        assertTrue(builderCss.contains("-fx-font-size: "
                + LauncherTypographyTokens.cssSize(
                LauncherTypographyTokens.FONT_SIZE_HELP_TITLE) + ";"));
        assertTrue(builderCss.contains("-fx-font-size: "
                + LauncherTypographyTokens.cssSize(
                LauncherTypographyTokens.FONT_SIZE_SECTION_TITLE) + ";"));
        assertTrue(builderCss.contains("-fx-border-width: "
                + LauncherTypographyTokens.cssSize(
                LauncherGeometryTokens.SURFACE_BORDER_WIDTH) + ";"));
        assertTrue(builderCss.contains("-fx-background-radius: "
                + LauncherTypographyTokens.cssSize(
                LauncherGeometryTokens.SURFACE_BEVEL_RADIUS) + ";"));
        assertTrue(builderCss.contains("-fx-background-radius: "
                + LauncherTypographyTokens.cssSize(
                LauncherGeometryTokens.CARD_BEVEL_RADIUS) + ";"));
        assertTrue(builderCss.contains("-fx-background-radius: "
                + LauncherTypographyTokens.cssSize(
                LauncherGeometryTokens.CONTROL_BEVEL_RADIUS) + ";"));
        assertTrue(builderCss.contains("-fx-min-width: "
                + LauncherTypographyTokens.cssSize(
                LauncherGeometryTokens.CONTROL_FIELD_HEIGHT) + ";"));
        assertFalse(builderCss.contains("-fx-font-size: -launcher-"));
        assertFalse(builderCss.contains("-fx-border-width: -launcher-"));
        assertFalse(builderCss.contains("-fx-background-radius: -launcher-"));
        assertFalse(builderCss.contains("-fx-border-radius: -launcher-"));
    }

    private static WorkflowBlockCatalog.WorkflowBlock block(
            String id,
            String inputType,
            String outputType) {
        return new WorkflowBlockCatalog.WorkflowBlock(
                "tool-" + id,
                id,
                id,
                id,
                "Test",
                inputType,
                outputType,
                List.of(id),
                "Tools>" + id,
                "astra/modules/tools/" + id + "/src/main/groovy/" + id + ".groovy");
    }
}
