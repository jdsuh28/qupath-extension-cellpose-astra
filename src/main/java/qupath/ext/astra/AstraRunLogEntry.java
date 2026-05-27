package qupath.ext.astra;

import java.util.Objects;

record AstraRunLogEntry(
        AstraRunLogSource source,
        AstraRunLogSeverity severity,
        AstraRunLogKind kind,
        String text,
        String rawText
) {

    AstraRunLogEntry {
        source = source == null ? AstraRunLogSource.SYSTEM : source;
        severity = severity == null ? AstraRunLogSeverity.NEUTRAL : severity;
        kind = kind == null ? AstraRunLogKind.MESSAGE : kind;
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

enum AstraRunLogKind {
    MESSAGE,
    KEY_VALUE,
    SEPARATOR,
    PROGRESS
}

enum AstraRunLogSeverity {
    INFO("INFO"),
    WARNING("WARNING"),
    ERROR("ERROR"),
    SUCCESS("SUCCESS"),
    CANCELLED("CANCELLED"),
    DEBUG("DEBUG"),
    NEUTRAL("NEUTRAL");

    private final String displayName;

    AstraRunLogSeverity(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }

    static AstraRunLogSeverity fromToken(String token, AstraRunLogSeverity fallback) {
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

enum AstraRunLogSource {
    ASTRA("ASTRA"),
    QUPATH("QuPath"),
    CELLPOSE("Cellpose"),
    PYTHON("Python"),
    SCRIPT("Script"),
    SYSTEM("System");

    private final String displayName;

    AstraRunLogSource(String displayName) {
        this.displayName = displayName;
    }

    String displayName() {
        return displayName;
    }
}
