package qupath.ext.biop.cellpose.astra;

import qupath.ext.biop.cellpose.Cellpose2D;

/**
 * ASTRA workflow hooks.
 * Legacy behavior remains unchanged unless explicitly triggered.
 */
public class AstraHooks {

    /**
     * Determines whether QC should be skipped after training.
     * Default behavior preserves legacy execution (QC runs).
     *
     * ASTRA workflow may override this behavior through configuration
     * or environment detection in the future.
     */
    public static boolean skipQC() {
        return false;
    }
}