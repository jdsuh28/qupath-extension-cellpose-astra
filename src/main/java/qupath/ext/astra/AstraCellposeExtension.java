package qupath.ext.astra;

import qupath.ext.biop.cellpose.CellposeExtension;

/**
 * ASTRA entrypoint for QuPath so the ASTRA fork is discovered as a distinct extension.
 *
 * Implementation intentionally delegates to the upstream BIOP CellposeExtension behavior,
 * overriding only identity/metadata (name/description/repository) to avoid collisions.
 */
public class AstraCellposeExtension extends CellposeExtension {

    @Override
    public String getName() {
        return "ASTRA Cellpose extension";
    }

    @Override
    public String getDescription() {
        return "ASTRA fork of the BIOP Cellpose extension (separate QuPath extension entry)";
    }

    @Override
    public GitHubRepo getRepository() {
        // Update to your fork identity for correct Extension Manager metadata.
        return GitHubRepo.create("ASTRA Cellpose 2D QuPath Extension", "jdsuh28", "qupath-extension-cellpose-astra");
    }
}
