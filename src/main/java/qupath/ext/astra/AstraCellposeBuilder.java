package qupath.ext.astra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cellpose.Cellpose2D;
import qupath.ext.biop.cellpose.CellposeBuilder;
import qupath.ext.biop.cellpose.OpCreators.TileOpCreator;
import qupath.lib.analysis.features.ObjectMeasurements.Compartments;
import qupath.lib.analysis.features.ObjectMeasurements.Measurements;
import qupath.lib.images.servers.ColorTransforms.ColorTransform;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;
import qupath.opencv.ops.ImageOp;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

/**
 * ASTRA-owned builder that preserves the upstream CellposeBuilder API surface
 * while returning an ASTRA-owned Cellpose2D subclass.
 *
 * This keeps ASTRA behavior out of upstream files and lets ASTRA scripts opt into
 * the ASTRA execution path explicitly.
 */
public class AstraCellposeBuilder extends CellposeBuilder {

    private static final Logger logger = LoggerFactory.getLogger(AstraCellposeBuilder.class);

    private File astraQcDirectory;
    private File astraResultsDirectory;

    public AstraCellposeBuilder(String modelPath) {
        super(modelPath);
    }

    public AstraCellposeBuilder(File builderFile) {
        super(builderFile);
    }

    @Override
    public AstraCellposeBuilder extendChannelOp(ImageOp extendChannelOp) {
        super.extendChannelOp(extendChannelOp);
        return this;
    }

    @Deprecated
    @Override
    public AstraCellposeBuilder useGPU(boolean useGPU) {
        super.useGPU(useGPU);
        return this;
    }

    @Override
    public AstraCellposeBuilder disableGPU() {
        super.disableGPU();
        return this;
    }

    @Override
    public AstraCellposeBuilder useTestDir(boolean useTestDir) {
        super.useTestDir(useTestDir);
        return this;
    }

    @Deprecated
    @Override
    public AstraCellposeBuilder saveTrainingImages(boolean saveTrainingImages) {
        super.saveTrainingImages(saveTrainingImages);
        return this;
    }

    @Override
    public AstraCellposeBuilder cleanTrainingDir() {
        super.cleanTrainingDir();
        return this;
    }

    @Override
    public AstraCellposeBuilder groundTruthDirectory(File groundTruthDirectory) {
        super.groundTruthDirectory(groundTruthDirectory);
        return this;
    }

    @Override
    public AstraCellposeBuilder tempDirectory(File trainingDirectory) {
        super.tempDirectory(trainingDirectory);
        return this;
    }

    @Override
    public AstraCellposeBuilder nThreads(int nThreads) {
        super.nThreads(nThreads);
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
    public AstraCellposeBuilder preprocess(ImageOp... ops) {
        super.preprocess(ops);
        return this;
    }

    @Override
    public AstraCellposeBuilder preprocessGlobal(TileOpCreator global) {
        super.preprocessGlobal(global);
        return this;
    }

    @Override
    public AstraCellposeBuilder simplify(double distance) {
        super.simplify(distance);
        return this;
    }

    @Override
    public AstraCellposeBuilder channels(int... channels) {
        super.channels(channels);
        return this;
    }

    @Override
    public AstraCellposeBuilder channels(String... channels) {
        super.channels(channels);
        return this;
    }

    @Override
    public AstraCellposeBuilder channels(ColorTransform... channels) {
        super.channels(channels);
        return this;
    }

    @Override
    public AstraCellposeBuilder cellExpansion(double distance) {
        super.cellExpansion(distance);
        return this;
    }

    @Override
    public AstraCellposeBuilder cellConstrainScale(double scale) {
        super.cellConstrainScale(scale);
        return this;
    }

    @Override
    public AstraCellposeBuilder createAnnotations() {
        super.createAnnotations();
        return this;
    }

    @Override
    public AstraCellposeBuilder classify(PathClass pathClass) {
        super.classify(pathClass);
        return this;
    }

    @Override
    public AstraCellposeBuilder classify(String pathClassName) {
        super.classify(pathClassName);
        return this;
    }

    @Override
    public AstraCellposeBuilder ignoreCellOverlaps(boolean ignore) {
        super.ignoreCellOverlaps(ignore);
        return this;
    }

    @Override
    public AstraCellposeBuilder constrainToParent(boolean constrainToParent) {
        super.constrainToParent(constrainToParent);
        return this;
    }

    @Override
    public AstraCellposeBuilder constrainToParent(boolean constrainToParent, double padding) {
        super.constrainToParent(constrainToParent, padding);
        return this;
    }

    @Override
    public AstraCellposeBuilder measureIntensity() {
        super.measureIntensity();
        return this;
    }

    @Override
    public AstraCellposeBuilder measureIntensity(Collection<Measurements> measurements) {
        super.measureIntensity(measurements);
        return this;
    }

    @Override
    public AstraCellposeBuilder measureShape() {
        super.measureShape();
        return this;
    }

    @Override
    public AstraCellposeBuilder compartments(Compartments... compartments) {
        super.compartments(compartments);
        return this;
    }

    @Override
    public AstraCellposeBuilder tileSize(int tileSize) {
        super.tileSize(tileSize);
        return this;
    }

    @Override
    public AstraCellposeBuilder tileSize(int tileWidth, int tileHeight) {
        super.tileSize(tileWidth, tileHeight);
        return this;
    }

    @Override
    public AstraCellposeBuilder normalizePercentiles(double min, double max) {
        super.normalizePercentiles(min, max);
        return this;
    }

    @Override
    public AstraCellposeBuilder normalizePercentiles(double min, double max, boolean perChannel, double eps) {
        super.normalizePercentiles(min, max, perChannel, eps);
        return this;
    }

    @Override
    public AstraCellposeBuilder inputAdd(double... values) {
        super.inputAdd(values);
        return this;
    }

    @Override
    public AstraCellposeBuilder inputSubtract(double... values) {
        super.inputSubtract(values);
        return this;
    }

    @Override
    public AstraCellposeBuilder inputScale(double... values) {
        super.inputScale(values);
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
    public AstraCellposeBuilder useOmnipose() {
        super.useOmnipose();
        return this;
    }

    @Override
    public AstraCellposeBuilder useCellposeSAM() {
        super.useCellposeSAM();
        return this;
    }

    @Override
    public AstraCellposeBuilder excludeEdges() {
        super.excludeEdges();
        return this;
    }

    @Override
    public AstraCellposeBuilder cellposeChannels(Integer channel1, Integer channel2) {
        super.cellposeChannels(channel1, channel2);
        return this;
    }

    @Deprecated
    @Override
    public AstraCellposeBuilder maskThreshold(Double threshold) {
        super.maskThreshold(threshold);
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
    public AstraCellposeBuilder diameter(Double diameter) {
        super.diameter(diameter);
        return this;
    }

    @Override
    public AstraCellposeBuilder modelDirectory(File modelDir) {
        super.modelDirectory(modelDir);
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
    public AstraCellposeBuilder saveBuilder(String name) {
        super.saveBuilder(name);
        return this;
    }

    @Override
    public AstraCellposeBuilder setOverlap(int overlap) {
        super.setOverlap(overlap);
        return this;
    }

    @Override
    public AstraCellposeBuilder normalizePercentilesGlobal(double percentileMin, double percentileMax, double normDownsample) {
        super.normalizePercentilesGlobal(percentileMin, percentileMax, normDownsample);
        return this;
    }

    @Override
    public AstraCellposeBuilder normalizePercentilesGlobal(String channelName, double percentileMin, double percentileMax, double normDownsample) {
        super.normalizePercentilesGlobal(channelName, percentileMin, percentileMax, normDownsample);
        return this;
    }

    @Override
    public AstraCellposeBuilder noCellposeNormalization() {
        super.noCellposeNormalization();
        return this;
    }

    @Override
    public AstraCellposeBuilder setOutputModelName(String outputName) {
        super.setOutputModelName(outputName);
        return this;
    }

    public AstraCellposeBuilder qcDirectory(File qcDir) {
        this.astraQcDirectory = qcDir;
        writeOptionalBuilderField("qcDirectory", qcDir);
        return this;
    }

    public File getQcDirectory() {
        return astraQcDirectory;
    }

    public AstraCellposeBuilder resultsDirectory(File resultsDir) {
        this.astraResultsDirectory = resultsDir;
        writeOptionalBuilderField("resultsDirectory", resultsDir);
        return this;
    }

    @Override
    public AstraCellpose2D build() {
        File projectDir = QP.getProject().getPath().getParent().toFile();

        File configuredModelDir = (File) readBuilderField("modelDirectory");
        File configuredTrainingRoot = (File) readBuilderField("groundTruthDirectory");
        File configuredTempDir = (File) readBuilderField("tempDirectory");

        try {
            File resolvedModelDir = AstraHooks.ensureDirectoryExists(
                    AstraHooks.resolveModelDirectory(projectDir, configuredModelDir));
            File resolvedTrainingRoot = AstraHooks.ensureDirectoryExists(
                    AstraHooks.resolveTrainingRootDirectory(projectDir, configuredTrainingRoot));
            File resolvedQcDir = AstraHooks.ensureDirectoryExists(
                    AstraHooks.resolveQcDirectory(projectDir, astraQcDirectory));
            File resolvedResultsDir = AstraHooks.ensureDirectoryExists(
                    AstraHooks.resolveResultsDirectory(projectDir, astraResultsDirectory));

            writeBuilderField("modelDirectory", resolvedModelDir);
            writeBuilderField("groundTruthDirectory", resolvedTrainingRoot);
            writeOptionalBuilderField("qcDirectory", resolvedQcDir);
            writeOptionalBuilderField("resultsDirectory", resolvedResultsDir);

            if (configuredTempDir == null) {
                writeBuilderField("tempDirectory", new File(projectDir, "cellpose-temp"));
            }

            Cellpose2D base = super.build();
            AstraCellpose2D astra = AstraCellpose2D.fromBase(base);
            astra.configureAstraState(
                    resolvedModelDir,
                    resolvedTrainingRoot,
                    readFileField(base, "tempDirectory"),
                    resolvedQcDir,
                    resolvedResultsDir
            );
            return astra;
        } catch (IOException e) {
            throw new RuntimeException("Failed to prepare ASTRA builder directories.", e);
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
