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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads ASTRA's manifest-backed runtime contract for the launcher.
 *
 * <p>The extension has no committed copy of the contract. Installed releases
 * read {@code astra/rulebook/manifests/master-contract.json} from the runtime JAR.
 * Local development and tests read the sibling base repo manifest when the
 * packaged resource is absent.</p>
 */
final class MasterContract {

    static final String BUNDLED_RESOURCE = "astra/rulebook/manifests/master-contract.json";
    static final Path LOCAL_MANIFEST = Path.of("../astra/rulebook/manifests/master-contract.json");
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, Object>>() {}.getType();

    private final Map<String, Object> root;

    /**
     * Creates a contract wrapper around parsed JSON.
     *
     * @param root parsed contract root.
     */
    private MasterContract(Map<String, Object> root) {
        this.root = Map.copyOf(Objects.requireNonNull(root, "root"));
    }

    /**
     * Loads the bundled contract, falling back to the sibling base manifest for local development.
     *
     * @return parsed contract wrapper.
     * @throws IllegalStateException when neither bundled nor local contract can be read.
     */
    static MasterContract load() {
        return load(MasterContract.class.getClassLoader(), LOCAL_MANIFEST);
    }

    /**
     * Loads a bundled contract resource with an explicit local fallback path.
     * This exists so tests can prove release JAR resource loading without the
     * sibling base checkout fallback.
     *
     * @param loader class loader used to find bundled resources.
     * @param localManifest optional local-development manifest fallback.
     * @return parsed contract wrapper.
     * @throws IllegalStateException when neither bundled nor local contract can be read.
     */
    static MasterContract load(ClassLoader loader, Path localManifest) {
        try (InputStream stream = loader.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (stream != null) {
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    return new MasterContract(parse(reader, BUNDLED_RESOURCE));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bundled ASTRA master contract: " + BUNDLED_RESOURCE, e);
        }

        if (Files.isRegularFile(localManifest)) {
            try (Reader reader = Files.newBufferedReader(localManifest, StandardCharsets.UTF_8)) {
                return new MasterContract(parse(reader, localManifest.toString()));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read local ASTRA master contract: " + localManifest, e);
            }
        }

        throw new IllegalStateException("Missing ASTRA master contract. Expected resource " + BUNDLED_RESOURCE
                + " or local file " + localManifest + ".");
    }

    /**
     * Loads a contract from an explicit file.
     *
     * @param path contract file path.
     * @return parsed contract wrapper.
     * @throws IllegalStateException when the file cannot be read.
     */
    static MasterContract load(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return new MasterContract(parse(reader, path.toString()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read ASTRA master contract: " + path, e);
        }
    }

    /**
     * Returns the root contract map.
     *
     * @return root contract map.
     */
    Map<String, Object> root() {
        return root;
    }

    /**
     * Resolves one pipeline by id or display name.
     *
     * @param idOrDisplay pipeline id or display name.
     * @return pipeline map if present.
     */
    Optional<Map<String, Object>> pipeline(String idOrDisplay) {
        String needle = normalize(idOrDisplay);
        for (Map<String, Object> pipeline : pipelines().values()) {
            if (normalize(stringValue(pipeline.get("id"))).equals(needle)
                    || normalize(stringValue(pipeline.get("displayName"))).equals(needle)) {
                return Optional.of(pipeline);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns contract-owned label overrides.
     *
     * @return label map.
     */
    Map<String, String> labels() {
        return stringMap(root.get("labels"));
    }

    /**
     * Returns contract-owned label token overrides.
     *
     * @return label token map.
     */
    Map<String, String> labelTokens() {
        return stringMap(root.get("labelTokens"));
    }

    /**
     * Returns contract-owned option label overrides.
     *
     * @return option label map.
     */
    Map<String, String> optionLabels() {
        return stringMap(root.get("optionLabels"));
    }

    /**
     * Resolves visible stage values for a pipeline.
     *
     * @param pipelineName pipeline id or display name.
     * @return visible stage values.
     */
    List<String> visibleStages(String pipelineName) {
        return pipeline(pipelineName)
                .map(p -> stringList(p.get("visibleStages")))
                .orElse(List.of());
    }

    /**
     * Resolves header actions for a pipeline.
     *
     * @param pipelineName pipeline id or display name.
     * @return header action values.
     */
    List<String> headerActions(String pipelineName) {
        return pipeline(pipelineName)
                .map(p -> stringList(p.get("headerActions")))
                .orElse(List.of());
    }

    /**
     * Resolves script action values for a pipeline.
     *
     * @param pipelineName pipeline id or display name.
     * @return script action values.
     */
    List<String> scriptActions(String pipelineName) {
        return pipeline(pipelineName)
                .map(p -> stringList(p.get("scriptActions")))
                .orElse(List.of());
    }

    /**
     * Parses a JSON reader into a root map.
     *
     * @param reader JSON reader.
     * @param source source label for diagnostics.
     * @return parsed root map.
     * @throws IllegalStateException when JSON is invalid or does not contain pipelines.
     */
    private static Map<String, Object> parse(Reader reader, String source) {
        try {
            Map<String, Object> parsed = GSON.fromJson(reader, MAP_TYPE);
            if (parsed == null || !(parsed.get("pipelines") instanceof Map<?, ?>)) {
                throw new IllegalStateException("ASTRA master contract missing pipelines: " + source);
            }
            return parsed;
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException("Invalid ASTRA master contract JSON: " + source, e);
        }
    }

    /**
     * Returns all pipeline maps keyed by id.
     *
     * @return pipeline map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> pipelines() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Object raw = root.get("pipelines");
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() instanceof Map<?, ?> value) {
                    out.put(String.valueOf(entry.getKey()), (Map<String, Object>) value);
                }
            }
        }
        return out;
    }

    /**
     * Converts a raw JSON object into a string map.
     *
     * @param raw raw JSON object.
     * @return string map.
     */
    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Converts a raw JSON array into a string list.
     *
     * @param raw raw JSON array.
     * @return string list.
     */
    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * Converts a raw value into a string.
     *
     * @param raw raw value.
     * @return string value or empty string.
     */
    private static String stringValue(Object raw) {
        return raw == null ? "" : String.valueOf(raw);
    }

    /**
     * Normalizes pipeline identifiers for display-name matching.
     *
     * @param value input value.
     * @return normalized identifier.
     */
    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
