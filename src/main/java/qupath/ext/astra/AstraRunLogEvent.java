package qupath.ext.astra;

import java.util.List;
import java.util.Map;

record AstraRunLogEvent(
        AstraRunLogEventType type,
        String pipelineId,
        String pipelineName,
        String stageId,
        String stageLabel,
        Integer imageIndex,
        Integer imageCount,
        Map<String, String> metrics,
        List<AstraRunLogKeyValue> keyValues,
        AstraRunLogEntry entry
) {

    AstraRunLogEvent {
        type = type == null ? AstraRunLogEventType.MESSAGE : type;
        pipelineId = pipelineId == null ? "" : pipelineId;
        pipelineName = pipelineName == null ? "" : pipelineName;
        stageId = stageId == null ? "" : stageId;
        stageLabel = stageLabel == null ? "" : stageLabel;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        keyValues = keyValues == null ? List.of() : List.copyOf(keyValues);
    }

    static AstraRunLogEvent of(AstraRunLogEventType type, String stageId, String stageLabel, AstraRunLogEntry entry) {
        return new AstraRunLogEvent(type, "", "", stageId, stageLabel, null, null, Map.of(), List.of(), entry);
    }
}

record AstraRunLogKeyValue(String key, String value) {
    AstraRunLogKeyValue {
        key = key == null ? "" : key.trim();
        value = value == null ? "" : value.trim();
    }
}

enum AstraRunLogEventType {
    START,
    PREFLIGHT,
    SCOPE,
    STAGE_START,
    STAGE_COMPLETE,
    EXTERNAL_RUNTIME,
    SAVE,
    EXPORT,
    RESET,
    WARNING,
    ERROR,
    COMPLETE,
    CANCELLED,
    MESSAGE
}
