package qupath.ext.astra;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads ASTRA's split rulebook manifests for the extension launcher.
 */
final class ManifestSet {

    static final String BUNDLED_ROOT = "astra/rulebook/manifests";
    static final String INDEX_RESOURCE = BUNDLED_ROOT + "/index.json";
    static final Path LOCAL_ROOT = Path.of("../astra/rulebook/manifests");
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();

    private final Map<String, Object> manifests;

    private ManifestSet(Map<String, Object> manifests) {
        this.manifests = Map.copyOf(Objects.requireNonNull(manifests, "manifests"));
    }

    static ManifestSet load() {
        return load(ManifestSet.class.getClassLoader(), LOCAL_ROOT);
    }

    static ManifestSet load(ClassLoader loader, Path localRoot) {
        try (InputStream stream = loader.getResourceAsStream(INDEX_RESOURCE)) {
            if (stream != null) {
                Map<String, Object> index = parse(new InputStreamReader(stream, StandardCharsets.UTF_8), INDEX_RESOURCE);
                return new ManifestSet(compose(index, path -> parseBundled(loader, BUNDLED_ROOT + "/" + path)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled ASTRA manifest index: " + INDEX_RESOURCE, e);
        }

        if (Files.isRegularFile(localRoot.resolve("index.json"))) {
            Map<String, Object> index = parseFile(localRoot.resolve("index.json"));
            return new ManifestSet(compose(index, path -> parseFile(localRoot.resolve(path))));
        }

        throw new IllegalStateException("Missing ASTRA manifest set. Expected resource " + INDEX_RESOURCE
                + " or local directory " + localRoot + ".");
    }

    static ManifestSet load(Path localRoot) {
        Map<String, Object> index = parseFile(localRoot.resolve("index.json"));
        return new ManifestSet(compose(index, path -> parseFile(localRoot.resolve(path))));
    }

    Map<String, Object> root() {
        return manifests;
    }

    Optional<Map<String, Object>> runnable(String idOrDisplay) {
        String needle = normalize(idOrDisplay);
        for (String id : runnableOrder()) {
            Map<String, Object> runnable = composedRunnable(id);
            if (normalize(stringValue(runnable.get("id"))).equals(needle)
                    || normalize(stringValue(runnable.get("displayName"))).equals(needle)
                    || normalize(stringValue(mapValue(runnable.get("gui")).get("menuPath"))).equals(needle)) {
                return Optional.of(runnable);
            }
        }
        return Optional.empty();
    }

    Map<String, String> scriptResources() {
        LinkedHashMap<String, String> scripts = new LinkedHashMap<>();
        for (String id : runnableOrder()) {
            Map<String, Object> gui = runnableGui(id);
            Map<String, Object> runtime = runnableRuntime(id);
            String menuPath = stringValue(gui.get("menuPath"));
            String entrypoint = stringValue(runtime.get("entrypointPath"));
            if (!menuPath.isBlank() && !entrypoint.isBlank()) {
                scripts.put(menuPath, "astra/" + entrypoint);
            }
        }
        return Collections.unmodifiableMap(scripts);
    }

    Map<String, String> labels() {
        return stringMap(gui().get("labels"));
    }

    Map<String, String> labelTokens() {
        return stringMap(gui().get("labelTokens"));
    }

    Map<String, String> optionLabels() {
        return stringMap(gui().get("optionLabels"));
    }

    Map<String, Object> advancedControls() {
        return mapValue(gui().get("advancedControls"));
    }

    List<String> visibleStages(String pipelineName) {
        return runnable(pipelineName)
                .map(p -> stringList(p.get("visibleStages")))
                .orElse(List.of());
    }

    List<String> headerActions(String pipelineName) {
        return runnable(pipelineName)
                .map(p -> stringList(p.get("headerActions")))
                .orElse(List.of());
    }

    List<String> scriptActions(String pipelineName) {
        return runnable(pipelineName)
                .map(p -> stringList(p.get("scriptActions")))
                .orElse(List.of());
    }

    List<String> workflowSequence(String pipelineName) {
        return runnable(pipelineName)
                .map(p -> stringList(mapValue(p.get("gui")).get("workflowSequence")))
                .orElse(List.of());
    }

    String workflowActiveLabel(String pipelineName) {
        return runnable(pipelineName)
                .map(p -> stringValue(mapValue(p.get("gui")).get("workflowActiveLabel")))
                .filter(s -> !s.isBlank())
                .orElse("");
    }

    String description(String pipelineName) {
        return runnable(pipelineName)
                .map(p -> stringValue(mapValue(p.get("gui")).get("description")))
                .filter(s -> !s.isBlank())
                .orElse("");
    }

    private Map<String, Object> composedRunnable(String id) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> inputs = runnableInputs(id);
        Map<String, Object> gui = runnableGui(id);
        Map<String, Object> runtime = runnableRuntime(id);
        out.put("id", id);
        out.put("displayName", gui.getOrDefault("displayName", inputs.get("displayName")));
        out.put("entrypoint", runtime.get("entrypointPath"));
        out.put("runner", runtime.get("runnerPath"));
        out.putAll(inputs);
        out.put("gui", gui);
        out.put("runtime", runtime);
        return Map.copyOf(out);
    }

    private static Map<String, Object> compose(Map<String, Object> index, ManifestReader reader) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        Map<String, Object> manifestRecords = recordsById(index.get("manifests"));
        for (String id : stringList(index.get("loadOrder"))) {
            Map<String, Object> record = mapValue(manifestRecords.get(id));
            String path = manifestFileName(record.getOrDefault("manifest", record.get("path")));
            if (path.isBlank()) {
                throw new IllegalStateException("ASTRA manifest index missing path for " + id + ".");
            }
            out.put(id, reader.read(path));
        }
        return out;
    }

    private static String manifestFileName(Object raw) {
        String path = stringValue(raw);
        int index = path.lastIndexOf('/');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private static Map<String, Object> recordsById(Object raw) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> record = mapValue(item);
                String id = stringValue(record.get("id"));
                if (!id.isBlank()) {
                    out.put(id, record);
                }
            }
        }
        return out;
    }

    private static Map<String, Object> parseBundled(ClassLoader loader, String resourcePath) {
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled ASTRA manifest resource: " + resourcePath);
            }
            return parse(new InputStreamReader(stream, StandardCharsets.UTF_8), resourcePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled ASTRA manifest resource: " + resourcePath, e);
        }
    }

    private static Map<String, Object> parseFile(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(reader, path.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ASTRA manifest: " + path, e);
        }
    }

    private static Map<String, Object> parse(Reader reader, String source) {
        try {
            Map<String, Object> parsed = GSON.fromJson(reader, MAP_TYPE);
            if (parsed == null) {
                throw new IllegalStateException("ASTRA manifest must be a JSON object: " + source);
            }
            return parsed;
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException("Invalid ASTRA manifest JSON: " + source, e);
        }
    }

    private List<String> runnableOrder() {
        List<String> menuOrder = stringList(gui().get("menuOrder"));
        return menuOrder.isEmpty() ? stringList(modules().get("runnableOrder")) : menuOrder;
    }

    private Map<String, Object> runnableRuntime(String id) {
        return mapValue(mapValue(runtime().get("runnables")).get(id));
    }

    private Map<String, Object> runnableInputs(String id) {
        return mapValue(mapValue(inputs().get("runnables")).get(id));
    }

    private Map<String, Object> runnableGui(String id) {
        return mapValue(mapValue(gui().get("runnables")).get(id));
    }

    private Map<String, Object> modules() {
        return mapValue(manifests.get("modules"));
    }

    private Map<String, Object> runtime() {
        return mapValue(manifests.get("runtime"));
    }

    private Map<String, Object> inputs() {
        return mapValue(manifests.get("inputs"));
    }

    private Map<String, Object> gui() {
        return mapValue(manifests.get("gui"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Map<String, String> stringMap(Object raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return Map.copyOf(out);
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    @FunctionalInterface
    private interface ManifestReader {
        Map<String, Object> read(String path);
    }
}
