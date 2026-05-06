package qupath.ext.astra;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cellpose.Cellpose2D;
import qupath.ext.biop.cellpose.CellposeBuilder;
import qupath.lib.io.GsonTools;
import qupath.lib.scripting.QP;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;

/**
 * Minimal ASTRA-owned builder surface required by the current training,
 * tuning, and validation stacks.
 *
 * This builder preserves the fluent methods the ASTRA stacks call,
 * resolves ASTRA-owned directories deterministically, and rejects runtime
 * selectors that violate the single-runtime-path contract.
 *
 * Downstream Groovy stacks own MODEL_SOURCE / MODEL_NAME policy.
 * By the time control reaches this builder, model selection has already been
 * resolved to one explicit execution model reference: either a promoted model
 * path or a shipped model name.
 */
public class AstraCellposeBuilder extends CellposeBuilder {

    private static final Logger logger = LoggerFactory.getLogger(AstraCellposeBuilder.class);

    private File validationDirectory;
    private File resultsDirectory;

    public AstraCellposeBuilder(String executionModelReference) {
        super(executionModelReference);
    }

    public AstraCellposeBuilder(File builderFile) {
        super(builderFile);
        loadSerializedBuilderState(builderFile);
    }

    @Override
    public AstraCellposeBuilder channels(String... channels) {
        super.channels(channels);
        return this;
    }

    @Override
    public AstraCellposeBuilder epochs(Integer nEpochs) {
        super.epochs(nEpochs);
        return this;
    }

    @Override
    public AstraCellposeBuilder learningRate(Double learningRate) {
        super.learningRate(learningRate);
        return this;
    }

    @Override
    public AstraCellposeBuilder addParameter(String flagName, String flagValue) {
        super.addParameter(flagName, flagValue);
        return this;
    }

    @Override
    public AstraCellposeBuilder addParameter(String flagName) {
        super.addParameter(flagName);
        return this;
    }

    @Override
    public AstraCellposeBuilder batchSize(Integer batchSize) {
        super.batchSize(batchSize);
        return this;
    }

    @Override
    public AstraCellposeBuilder minTrainMasks(Integer n) {
        super.minTrainMasks(n);
        return this;
    }

    @Override
    public AstraCellposeBuilder groundTruthDirectory(File groundTruthDirectory) {
        super.groundTruthDirectory(groundTruthDirectory);
        return this;
    }

    @Override
    public AstraCellposeBuilder tempDirectory(File tempDirectory) {
        super.tempDirectory(tempDirectory);
        return this;
    }

    @Override
    public AstraCellposeBuilder useTestDir(boolean useTestDir) {
        super.useTestDir(useTestDir);
        return this;
    }

    @Override
    public AstraCellposeBuilder cleanTrainingDir() {
        super.cleanTrainingDir();
        return this;
    }

    @Override
    public AstraCellposeBuilder setOverlap(int overlap) {
        super.setOverlap(overlap);
        return this;
    }

    @Override
    public AstraCellposeBuilder nThreads(int nThreads) {
        super.nThreads(nThreads);
        return this;
    }

    @Override
    public AstraCellposeBuilder modelDirectory(File modelDir) {
        super.modelDirectory(modelDir);
        return this;
    }

    @Override
    public AstraCellposeBuilder readResultsAsynchronously() {
        super.readResultsAsynchronously();
        return this;
    }

    @Override
    public AstraCellposeBuilder pixelSize(double pixelSize) {
        super.pixelSize(pixelSize);
        return this;
    }

    @Override
    public AstraCellposeBuilder constrainToParent(boolean constrainToParent) {
        super.constrainToParent(constrainToParent);
        return this;
    }

    @Override
    public AstraCellposeBuilder diameter(Double diameter) {
        super.diameter(diameter);
        return this;
    }

    @Override
    public AstraCellposeBuilder cellprobThreshold(Double threshold) {
        super.cellprobThreshold(threshold);
        return this;
    }

    @Override
    public AstraCellposeBuilder flowThreshold(Double threshold) {
        super.flowThreshold(threshold);
        return this;
    }

    @Override
    public AstraCellposeBuilder simplify(double distance) {
        super.simplify(distance);
        return this;
    }

    @Override
    public AstraCellposeBuilder normalizePercentiles(double min, double max) {
        super.normalizePercentiles(min, max);
        return this;
    }

    @Override
    public AstraCellposeBuilder useGPU(boolean useGPU) {
        super.useGPU(useGPU);
        return this;
    }

    @Override
    public AstraCellposeBuilder useCellposeSAM() {
        throw new UnsupportedOperationException(
                "ASTRA uses a single runtime path and does not support useCellposeSAM()."
        );
    }

    @Override
    public AstraCellposeBuilder useOmnipose() {
        throw new UnsupportedOperationException(
                "ASTRA uses a single runtime path and does not support Omnipose selection."
        );
    }

    /**
     * Configure the ASTRA validation input directory.
     */
    public AstraCellposeBuilder validationDirectory(File validationDir) {
        this.validationDirectory = validationDir;
        return this;
    }

    /**
     * Configure the ASTRA results root directory.
     */
    public AstraCellposeBuilder resultsDirectory(File resultsDir) {
        this.resultsDirectory = resultsDir;
        writeOptionalBuilderField("resultsDirectory", resultsDir);
        return this;
    }

    @Override
    public AstraCellpose2D build() {
        File projectDirectory = requireProjectDirectory();

        File configuredModelDirectory = (File) readBuilderField("modelDirectory");
        File configuredTrainingRoot = (File) readBuilderField("groundTruthDirectory");
        File configuredTempDirectory = (File) readBuilderField("tempDirectory");
        boolean shouldSaveBuilder = Boolean.TRUE.equals(readBuilderField("saveBuilder"));
        String builderName = (String) readBuilderField("builderName");

        try {
            File resolvedModelDirectory = AstraCellpose2D.ensureDirectoryExists(
                    AstraCellpose2D.resolveModelDirectory(projectDirectory, configuredModelDirectory));
            File resolvedTrainingRoot = AstraCellpose2D.ensureDirectoryExists(
                    AstraCellpose2D.resolveTrainingRootDirectory(projectDirectory, configuredTrainingRoot));
            File resolvedTempDirectory = AstraCellpose2D.ensureDirectoryExists(
                    configuredTempDirectory != null ? configuredTempDirectory : new File(projectDirectory, "cellpose-temp"));
            File resolvedValidationDirectory = AstraCellpose2D.ensureDirectoryExists(
                    AstraCellpose2D.resolveValidationInputDirectory(projectDirectory, validationDirectory));
            File resolvedResultsDirectory = AstraCellpose2D.ensureDirectoryExists(
                    AstraCellpose2D.resolveResultsDirectory(projectDirectory, resultsDirectory));

            this.validationDirectory = resolvedValidationDirectory;
            this.resultsDirectory = resolvedResultsDirectory;

            writeBuilderField("modelDirectory", resolvedModelDirectory);
            writeBuilderField("groundTruthDirectory", resolvedTrainingRoot);
            writeBuilderField("tempDirectory", resolvedTempDirectory);
            writeOptionalBuilderField("resultsDirectory", resolvedResultsDirectory);

            if (shouldSaveBuilder) {
                writeBuilderField("saveBuilder", false);
            }

            Cellpose2D base = super.build();
            AstraCellpose2D runtime = AstraCellpose2D.fromBase(base);
            runtime.configureRuntimeState(
                    resolvedModelDirectory,
                    resolvedTrainingRoot,
                    resolvedTempDirectory,
                    resolvedValidationDirectory,
                    resolvedResultsDirectory
            );

            if (shouldSaveBuilder) {
                saveSerializedBuilderState(resolvedModelDirectory, builderName);
            }

            return runtime;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare ASTRA builder directories.", e);
        } finally {
            if (shouldSaveBuilder) {
                writeBuilderField("saveBuilder", true);
            }
        }
    }


    private static File requireProjectDirectory() {
        if (QP.getProject() == null) {
            throw new IllegalStateException("ASTRA builder requires an open QuPath project.");
        }
        if (QP.getProject().getPath() == null || QP.getProject().getPath().getParent() == null) {
            throw new IllegalStateException("ASTRA builder could not resolve the project directory.");
        }
        return QP.getProject().getPath().getParent().toFile();
    }

    private void loadSerializedBuilderState(File builderFile) {
        Gson gson = GsonTools.getInstance();
        try (FileReader reader = new FileReader(builderFile)) {
            AstraCellposeBuilder loaded = gson.fromJson(reader, AstraCellposeBuilder.class);
            if (loaded == null) {
                throw new IllegalStateException("Serialized ASTRA builder file produced no builder state: " + builderFile.getAbsolutePath());
            }
            copyBuilderState(loaded, this);
            logger.info("ASTRA builder parameters loaded from {}", builderFile);
        } catch (IOException e) {
            throw new IllegalStateException("Could not load ASTRA builder from " + builderFile.getAbsolutePath(), e);
        }
    }

    private void saveSerializedBuilderState(File modelDirectory, String builderName) {
        Gson gson = GsonTools.getInstance(true);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH'h'mm");
        LocalDateTime now = LocalDateTime.now();
        String safeBuilderName = builderName == null || builderName.isBlank() ? "builder" : builderName.trim();
        File savePath = new File(modelDirectory, safeBuilderName + "_" + dtf.format(now) + ".json");

        try (FileWriter fw = new FileWriter(savePath)) {
            gson.toJson(this, AstraCellposeBuilder.class, fw);
            fw.flush();
            logger.info("Serialized ASTRA builder saved to {}", savePath);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save serialized ASTRA builder to " + savePath.getAbsolutePath(), e);
        }
    }

    private static void copyBuilderState(AstraCellposeBuilder source, AstraCellposeBuilder target) {
        copyFields(source, target, AstraCellposeBuilder.class);
        copyFields(source, target, CellposeBuilder.class);
    }

    private static void copyFields(Object source, Object target, Class<?> owner) {
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(source);
                if (Modifier.isFinal(field.getModifiers())) {
                    Object existing = field.get(target);
                    if (existing instanceof Collection<?> existingCollection && value instanceof Collection<?> valueCollection) {
                        copyCollectionContents(existingCollection, valueCollection);
                    } else if (existing instanceof Map<?, ?> existingMap && value instanceof Map<?, ?> valueMap) {
                        copyMapContents(existingMap, valueMap);
                    }
                    continue;
                }
                field.set(target, value);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to copy field '" + field.getName() + "' from " + owner.getName(), e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void copyCollectionContents(Collection<?> target, Collection<?> source) {
        Collection<Object> targetObjects = (Collection<Object>) target;
        targetObjects.clear();
        targetObjects.addAll(source);
    }

    @SuppressWarnings("unchecked")
    private static void copyMapContents(Map<?, ?> target, Map<?, ?> source) {
        Map<Object, Object> targetMap = (Map<Object, Object>) target;
        targetMap.clear();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            targetMap.put(entry.getKey(), entry.getValue());
        }
    }

    private Object readBuilderField(String name) {
        return readField(this, CellposeBuilder.class, name, false);
    }

    private void writeBuilderField(String name, Object value) {
        writeField(this, CellposeBuilder.class, name, value, false);
    }

    private void writeOptionalBuilderField(String name, Object value) {
        writeField(this, CellposeBuilder.class, name, value, true);
    }

    static File readFileField(Object target, String name) {
        Object value = readFieldFromHierarchy(target, name);
        return value instanceof File ? (File) value : null;
    }

    private static Object readFieldFromHierarchy(Object target, String name) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                return readField(target, current, name, false);
            } catch (IllegalStateException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object readField(Object target, Class<?> owner, String name, boolean optional) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            if (optional) {
                return null;
            }
            throw new IllegalStateException("Unable to read field '" + name + "' from " + owner.getName(), e);
        }
    }

    private static void writeField(Object target, Class<?> owner, String name, Object value, boolean optional) {
        try {
            Field field = owner.getDeclaredField(name);
            if (Modifier.isFinal(field.getModifiers())) {
                throw new IllegalStateException("Field '" + name + "' is final on " + owner.getName());
            }
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            if (!optional) {
                throw new IllegalStateException("Unable to write field '" + name + "' on " + owner.getName(), e);
            }
        }
    }
}
