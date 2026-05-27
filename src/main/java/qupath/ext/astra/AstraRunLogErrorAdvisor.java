package qupath.ext.astra;

import java.util.Locale;

final class AstraRunLogErrorAdvisor {

    private AstraRunLogErrorAdvisor() {
        throw new AssertionError("No instances");
    }

    static AstraRunLogErrorAdvice advise(AstraRunLogEvent event) {
        AstraRunLogEntry entry = event == null ? null : event.entry();
        String stage = event == null ? "" : event.stageLabel();
        return advise(entry, stage);
    }

    static AstraRunLogErrorAdvice advise(AstraRunLogEntry entry, String stageLabel) {
        String text = entry == null ? "" : entry.text();
        String upper = text.toUpperCase(Locale.ROOT);
        AstraRunLogSource source = entry == null ? AstraRunLogSource.SYSTEM : entry.source();
        String stage = stageLabel == null ? "" : stageLabel.trim();

        if (upper.contains("CANCEL")) {
            return advice("Cancelled run", text, "The run was cancelled by the user or runtime.",
                    "Wait for any native Cellpose process to exit before starting another run.", source, stage);
        }
        if (upper.contains("FINITE") || upper.contains("NAN") || upper.contains("INFINITY") || upper.contains("THRESHOLD")) {
            return advice("Non-finite measurement or threshold", text,
                    "A measurement or threshold resolved to NaN or Infinity, usually from empty, invalid, or saturated source values.",
                    "Check the affected marker/compartment and rerun after confirming finite measurements are available.", source, stage);
        }
        if (upper.contains("NO CELLS") || upper.contains("CELLS CREATED     : 0") || upper.contains("NO DETECTION") || upper.contains("NO DETECTIONS")) {
            return advice("No cells available", text,
                    "The requested stage had no cells or detections to quantify.",
                    "Confirm the ROI contains detectable nuclei/cells and that detection completed before quantification.", source, stage);
        }
        if (upper.contains("NO SUCH PROPERTY") || upper.contains("UNABLE TO RESOLVE CLASS") || upper.contains("MISSINGPROPERTY") || upper.contains("CLASSNOTFOUND")) {
            return advice("Missing runtime class/property", text,
                    "The runtime script could not resolve a bundled ASTRA helper, class, or property.",
                    "Install the latest extension JAR and confirm the bundled ASTRA resources match the release.", source, stage);
        }
        if (upper.contains("CELLPOS") || upper.contains("VIRTUAL ENVIRONMENT RUNNER") || upper.contains("SUBPROCESS") || upper.contains("EXIT CODE")) {
            return advice("Cellpose runtime failure", text,
                    "The external Cellpose process failed or reported an error.",
                    "Check the Python environment, model path, GPU/backend message, and Cellpose log output above.", source, stage);
        }
        if (upper.contains("PYTHON") || upper.contains("TORCH") || upper.contains("MPS") || upper.contains("CONDA") || upper.contains("ENVIRONMENT")) {
            return advice("Python environment failure", text,
                    "The configured Python environment or backend appears inconsistent with the requested run.",
                    "Verify the selected environment launches Cellpose successfully outside QuPath.", source, stage);
        }
        if (upper.contains("EXPORT") && (upper.contains("STATE") || upper.contains("LEDGER") || upper.contains("ACTIVE"))) {
            return advice("Export state mismatch", text,
                    "The export stage could not reconcile the current image/run state.",
                    "Rerun detection/quantification for the active image, then export from the same project state.", source, stage);
        }
        if (upper.contains("NO SUCH FILE") || upper.contains("NOT FOUND") || upper.contains("MISSING") || upper.contains("RESOURCE")) {
            return advice("Missing file or resource", text,
                    "A required model, file, or bundled resource could not be found.",
                    "Check the path shown in the error and reinstall or regenerate the missing resource.", source, stage);
        }
        return advice("Run failed", text,
                "ASTRA reported an error that does not match a known advisory pattern.",
                "Review the original error line and nearby log context.", source, stage);
    }

    private static AstraRunLogErrorAdvice advice(String family, String message, String likelyCause, String nextAction,
                                                AstraRunLogSource source, String stage) {
        return new AstraRunLogErrorAdvice(family, message, likelyCause, nextAction, source, stage);
    }
}

record AstraRunLogErrorAdvice(
        String family,
        String message,
        String likelyCause,
        String nextAction,
        AstraRunLogSource source,
        String stage
) {
    AstraRunLogErrorAdvice {
        family = family == null ? "" : family.trim();
        message = message == null ? "" : message.trim();
        likelyCause = likelyCause == null ? "" : likelyCause.trim();
        nextAction = nextAction == null ? "" : nextAction.trim();
        source = source == null ? AstraRunLogSource.SYSTEM : source;
        stage = stage == null ? "" : stage.trim();
    }
}
