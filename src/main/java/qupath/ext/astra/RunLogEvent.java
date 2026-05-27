package qupath.ext.astra;

import java.util.List;
import java.util.Map;

record RunLogEvent(
        RunLogEventType type,
        String pipelineId,
        String pipelineName,
        String stageId,
        String stageLabel,
        Integer imageIndex,
        Integer imageCount,
        Map<String, String> metrics,
        List<RunLogKeyValue> keyValues,
        RunLogEntry entry
) {

    RunLogEvent {
        type = type == null ? RunLogEventType.MESSAGE : type;
        pipelineId = pipelineId == null ? "" : pipelineId;
        pipelineName = pipelineName == null ? "" : pipelineName;
        stageId = stageId == null ? "" : stageId;
        stageLabel = stageLabel == null ? "" : stageLabel;
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        keyValues = keyValues == null ? List.of() : List.copyOf(keyValues);
    }

    static RunLogEvent of(RunLogEventType type, String stageId, String stageLabel, RunLogEntry entry) {
        return new RunLogEvent(type, "", "", stageId, stageLabel, null, null, Map.of(), List.of(), entry);
    }
}

record RunLogKeyValue(String key, String value) {
    RunLogKeyValue {
        key = key == null ? "" : key.trim();
        value = value == null ? "" : value.trim();
    }
}

enum RunLogEventType {
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
