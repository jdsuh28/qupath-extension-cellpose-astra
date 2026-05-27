package qupath.ext.astra;

import java.util.Objects;

record RunLogEntry(
        RunLogSource source,
        RunLogSeverity severity,
        RunLogKind kind,
        String text,
        String rawText
) {

    RunLogEntry {
        source = source == null ? RunLogSource.SYSTEM : source;
        severity = severity == null ? RunLogSeverity.NEUTRAL : severity;
        kind = kind == null ? RunLogKind.MESSAGE : kind;
        text = Objects.requireNonNullElse(text, "").trim();
        rawText = Objects.requireNonNullElse(rawText, "");
    }

    String copyText() {
        if (text.isBlank()) {
            return "";
        }
        return "[" + source.displayName() + "][" + severity.displayName() + "] " + text;
    }

    boolean isProgressLine() {
        return text.matches("^\\d+%\\|.*") || text.contains("it/s]");
    }
}

enum RunLogKind {
    MESSAGE,
    KEY_VALUE,
    SEPARATOR,
    PROGRESS
}

enum RunLogSeverity {
    INFO("INFO"),
    WARNING("WARNING"),
    ERROR("ERROR"),
    SUCCESS("SUCCESS"),
    CANCELLED("CANCELLED"),
    DEBUG("DEBUG"),
    NEUTRAL("NEUTRAL");

    private final String displayName;

    RunLogSeverity(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    static RunLogSeverity fromToken(String token, RunLogSeverity fallback) {
        if (token == null || token.isBlank()) {
            return fallback == null ? NEUTRAL : fallback;
        }
        return switch (token.trim().toUpperCase()) {
            case "WARN", "WARNING" -> WARNING;
            case "ERROR", "ERR", "SEVERE" -> ERROR;
            case "DONE", "SUCCESS", "COMPLETE", "COMPLETED" -> SUCCESS;
            case "CANCEL", "CANCELLED", "CANCELED" -> CANCELLED;
            case "DEBUG", "TRACE" -> DEBUG;
            case "INFO" -> INFO;
            default -> fallback == null ? NEUTRAL : fallback;
        };
    }
}

enum RunLogSource {
    ASTRA("ASTRA"),
    QUPATH("QuPath"),
    CELLPOSE("Cellpose"),
    PYTHON("Python"),
    SCRIPT("Script"),
    SYSTEM("System");

    private final String displayName;

    RunLogSource(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }
}
