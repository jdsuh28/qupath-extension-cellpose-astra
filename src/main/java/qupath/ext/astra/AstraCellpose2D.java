package qupath.ext.astra;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.Wand;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.simplify.VWSimplifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cellpose.Cellpose2D;
import qupath.ext.biop.cellpose.OpCreators;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.fx.utils.FXUtils;
import qupath.imagej.tools.IJTools;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.common.ColorTools;
import qupath.lib.common.GeneralTools;
import qupath.lib.geom.ImmutableDimension;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.*;
import qupath.lib.objects.CellTools;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.ops.ImageDataOp;
import qupath.opencv.ops.ImageDataServer;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;
import qupath.lib.images.writers.ImageWriterTools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ASTRA-owned Cellpose2D subclass.
 *
 * This class owns the ASTRA runtime contract for training image export,
 * training-result handling, validation evaluation, and batch inference.
 * ASTRA policy remains explicit and fail-fast: downstream stacks resolve
 * model-selection policy before invoking this runtime.
 */
public class AstraCellpose2D extends Cellpose2D {

    private static final Logger logger = LoggerFactory.getLogger(AstraCellpose2D.class);

    private static final String VALIDATION_METRICS_HELPER_RELATIVE_PATH = "QC/run-cellpose-qc.py";

    private File validationDirectory;
    private File resultsDirectory;
    private ResultsTable validationResults;
    private boolean batchInferenceEnabled = true;
    private boolean pixelScalingEnabled = true;
    private Double lastCanonicalPixelSizeUsed = null;
    private String trainingAnnotationClass = null;

    public AstraCellpose2D() {
        super();
    }

    public static AstraCellposeBuilder builder(String executionModelReference) {
        return new AstraCellposeBuilder(executionModelReference);
    }

    public static AstraCellposeBuilder builder(File builderPath) {
        return new AstraCellposeBuilder(builderPath);
    }

    public void setBatchInferenceEnabled(boolean enabled) {
        this.batchInferenceEnabled = enabled;
    }

    public void setPixelScalingEnabled(boolean enabled) {
        this.pixelScalingEnabled = enabled;
    }

    public Double getCanonicalPixelSizeUsed() {
        return lastCanonicalPixelSizeUsed;
    }

    private void clearLastCanonicalPixelSizeUsed() {
        lastCanonicalPixelSizeUsed = null;
    }

    public void setTrainingAnnotationClass(String pathClassName) {
        if (pathClassName == null) {
            this.trainingAnnotationClass = null;
            return;
        }
        String trimmed = pathClassName.trim();
        this.trainingAnnotationClass = trimmed.isEmpty() ? null : trimmed;
    }

    static AstraCellpose2D fromBase(Cellpose2D base) {
        AstraCellpose2D runtime = new AstraCellpose2D();
        copyCellpose2DState(base, runtime);
        return runtime;
    }


    void configureRuntimeState(File modelDirectory, File trainingRootDirectory, File tempDirectory, File validationDirectory, File resultsDirectory) {
        this.modelDirectory = modelDirectory;
        this.groundTruthDirectory = trainingRootDirectory;
        this.validationDirectory = validationDirectory;
        this.resultsDirectory = resultsDirectory;
        setTempDirectoryField(tempDirectory);
    }

    @Override
    public File getTrainingDirectory() {
        return resolveTrainingDirectory(groundTruthDirectory);
    }

    @Override
    public File getValidationDirectory() {
        return resolveValidationInputDirectory(validationDirectory);
    }

    public ResultsTable getValidationResults() {
        return validationResults;
    }

    @Override
    public void saveTrainingImages() {
        clearLastCanonicalPixelSizeUsed();

        double previousPixelSize = pixelSize;
        boolean restorePixelSize = false;
        String trainingAnnotationClass = this.trainingAnnotationClass;

        try {
            if (!pixelScalingEnabled) {
                pixelSize = Double.NaN;
                restorePixelSize = true;
            } else {
                if (!Double.isFinite(pixelSize) || pixelSize <= 0) {
                    Double resolvedPixelSize = resolveTrainingCanonicalPixelSizeFromProject();
                    if (resolvedPixelSize != null && Double.isFinite(resolvedPixelSize) && resolvedPixelSize > 0) {
                        pixelSize = resolvedPixelSize;
                        restorePixelSize = true;
                        logger.info("ASTRA training auto-resolved canonical working pixel size to {} um/px.", resolvedPixelSize);
                    }
                }

                if (Double.isFinite(pixelSize) && pixelSize > 0) {
                    lastCanonicalPixelSizeUsed = pixelSize;
                }
            }

            if (trainingAnnotationClass == null || trainingAnnotationClass.isBlank()) {
                throw new IllegalStateException(
                        "ASTRA training image export requires trainingAnnotationClass to be set explicitly."
                );
            }

            saveTrainingImagesForClass(trainingAnnotationClass);
        } finally {
            if (restorePixelSize) {
                pixelSize = previousPixelSize;
            }
        }
    }


    private void saveTrainingImagesForClass(String trainingAnnotationClass) {
        File trainDirectory = getTrainingDirectory();
        File validationDirectory = getValidationDirectory();
        try {
            ensureDirectoryExists(trainDirectory);
            ensureDirectoryExists(validationDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare training image export directories.", e);
        }

        var qupath = QPEx.getQuPath();
        if (qupath == null || qupath.getProject() == null) {
            throw new IllegalStateException("ASTRA training image export requires an open QuPath project.");
        }

        for (var entry : qupath.getProject().getImageList()) {
            final String entryName = entry.getImageName();
            try {
                ImageData<BufferedImage> imageData = castImageData(entry.readImageData());
                if (imageData == null) {
                    throw new IllegalStateException("Could not read image data for training export.");
                }

                if (this.extendChannelOp != null) {
                    ImageServer<BufferedImage> avgServer = new TransformedServerBuilder(imageData.getServer()).averageChannelProject().build();
                    ImageData<BufferedImage> avgImageData = new ImageData<>(avgServer, imageData.getHierarchy(), ImageData.ImageType.OTHER);
                    ImageDataOp op2 = ImageOps.buildImageDataOp(ColorTransforms.createMeanChannelTransform());
                    op2 = op2.appendOps(extendChannelOp);
                    ImageServer<BufferedImage> opServer = ImageOps.buildServer(avgImageData, op2, imageData.getServer().getPixelCalibration());
                    ImageServer<BufferedImage> combinedServer = concatChannelsSafely(avgServer, opServer);
                    imageData = new ImageData<>(combinedServer, imageData.getHierarchy(), ImageData.ImageType.OTHER);
                }

                String imageName = GeneralTools.stripExtension(imageData.getServer().getMetadata().getName());
                Collection<PathObject> allAnnotations = imageData.getHierarchy().getAnnotationObjects();

                List<PathObject> trainingAnnotations = allAnnotations.stream()
                        .filter(a -> hasExactPathClass(a, "Training"))
                        .collect(Collectors.toList());
                List<PathObject> validationAnnotations = allAnnotations.stream()
                        .filter(a -> hasExactPathClass(a, "Validation"))
                        .collect(Collectors.toList());

                PixelCalibration resolution = imageData.getServer().getPixelCalibration();
                if (Double.isFinite(pixelSize) && pixelSize > 0) {
                    double downsample = pixelSize / resolution.getAveragedPixelSize().doubleValue();
                    resolution = resolution.createScaledInstance(downsample, downsample);
                }

                logger.info("Found {} Training objects and {} Validation objects in image {}", trainingAnnotations.size(), validationAnnotations.size(), imageName);
                if (trainingAnnotations.isEmpty() && validationAnnotations.isEmpty()) {
                    continue;
                }

                ImageDataOp opWithPreprocessing = buildTrainingPreprocessingOp(imageData);
                ImageServer<BufferedImage> processed = ImageOps.buildServer(imageData, opWithPreprocessing, resolution, tileWidth, tileHeight);
                LabeledImageServer labelServer = new LabeledImageServer.Builder(imageData)
                        .backgroundLabel(0, ColorTools.BLACK)
                        .multichannelOutput(false)
                        .useInstanceLabels()
                        .useFilter(o -> hasExactPathClass(o, trainingAnnotationClass))
                        .build();

                saveTrainingImagePairs(trainingAnnotations, imageName, processed, labelServer, trainDirectory);
                saveTrainingImagePairs(validationAnnotations, imageName, processed, labelServer, validationDirectory);
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "ASTRA training image export failed for project entry '" + entryName + "'.",
                        ex
                );
            }
        }
    }

    private ImageDataOp buildTrainingPreprocessingOp(ImageData<BufferedImage> imageData) throws IOException, InterruptedException {
        ArrayList<ImageOp> fullPreprocess = new ArrayList<>();
        fullPreprocess.add(ImageOps.Core.ensureType(PixelType.FLOAT32));

        if (globalPreprocess) {
            if (globalPreprocessingProvidedByUser == null) {
                List<ImageOp> splitMergeListImageOp = new ArrayList<>();
                for (Map.Entry<ColorTransforms.ColorTransform, Map<String, Double>> entry : this.normalizeChannelPercentilesGlobalMap.entrySet()) {
                    splitMergeListImageOp.add(createNormalizationImageOp(entry.getKey(), entry.getValue(), imageData, null, null));
                }
                fullPreprocess.add(ImageOps.Core.splitMerge(splitMergeListImageOp));
            } else {
                Object normalizeOps = globalPreprocessingProvidedByUser.createOps(op, imageData, null, null);
                if (normalizeOps instanceof Collection<?> normalizeOpCollection) {
                    for (Object normalizeOp : normalizeOpCollection) {
                        fullPreprocess.add((ImageOp) normalizeOp);
                    }
                } else if (normalizeOps instanceof ImageOp normalizeOp) {
                    fullPreprocess.add(normalizeOp);
                } else if (normalizeOps != null) {
                    throw new IllegalStateException("Unexpected preprocessing op payload type: " + normalizeOps.getClass().getName());
                }
            }
            this.parameters.put("no_norm", null);
        }

        if (preprocess != null && !preprocess.isEmpty()) {
            fullPreprocess.addAll(preprocess);
        }

        if (fullPreprocess.size() > 1) {
            fullPreprocess.add(ImageOps.Core.ensureType(PixelType.FLOAT32));
        }

        return op.appendOps(fullPreprocess.toArray(ImageOp[]::new));
    }

    private void saveTrainingImagePairs(List<PathObject> annotations, String imageName, ImageServer<BufferedImage> originalServer, ImageServer<BufferedImage> labelServer, File saveDirectory) {
        if (annotations == null || annotations.isEmpty()) {
            return;
        }

        final double downsample = (Double.isFinite(pixelSize) && pixelSize > 0)
                ? pixelSize / originalServer.getPixelCalibration().getAveragedPixelSize().doubleValue()
                : 1.0d;

        for (int i = 0; i < annotations.size(); i++) {
            PathObject annotation = annotations.get(i);
            RegionRequest request = RegionRequest.createInstance(originalServer.getPath(), downsample, annotation.getROI());
            if (request.getWidth() < 10 || request.getHeight() < 10) {
                throw new IllegalStateException(
                        "ASTRA training image export produced a tile that is too small to be valid: " + request
                );
            }

            File imageFile = new File(saveDirectory, imageName + "_region_" + i + ".tif");
            File maskFile = new File(saveDirectory, imageName + "_region_" + i + "_masks.tif");
            try {
                ImageWriterTools.writeImageRegion(originalServer, request, imageFile.getAbsolutePath());
                ImageWriterTools.writeImageRegion(labelServer, request, maskFile.getAbsolutePath());
                logger.info("Saved image pair: {} | {}", imageFile.getName(), maskFile.getName());
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "ASTRA training image export failed while writing '" + imageFile.getName() + "' and '" + maskFile.getName() + "'. " +
                                "Please verify channel names and preprocessing configuration.",
                        ex
                );
            }
        }
    }

    private static boolean hasExactPathClass(PathObject object, String className) {
        if (object == null || className == null) {
            return false;
        }
        PathClass pathClass = object.getPathClass();
        return pathClass != null && className.equals(pathClass.toString());
    }

    @Override
    public File train() {
        requireExecutionModelReference(this);
        try {
            if (this.cleanTrainingDir) {
                resetDirectory(this.groundTruthDirectory);
                saveTrainingImages();
            }

            runTrainingCommand();

            ResultsTable parsedTrainingResults = parseTrainingResults();
            setTrainingResults(parsedTrainingResults);
            saveTrainingGraphAfterTraining();
            logger.info("Training completed. Results processed and ready.");

            return trainingArtifactReturnValue(getTrainingDirectory());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASTRA training was interrupted.", e);
        } catch (IOException e) {
            throw new IllegalStateException("ASTRA training failed.", e);
        }
    }

    public ResultsTable runValidation() throws IOException, InterruptedException {
        requireValidationInputDirectory();
        requireExecutionModelReference(this);
        generateValidationPredictions();
        ResultsTable results = computeValidationMetricsWithHelper();
        setValidationResults(results);
        return results;
    }

    @Override
    public void showTrainingGraph(boolean show, boolean save) {
        throw new UnsupportedOperationException(
                "ASTRA does not expose public training-graph display hooks. Training graph export is handled internally during train()."
        );
    }

    @Override
    public void showTrainingGraph() {
        throw new UnsupportedOperationException(
                "ASTRA does not expose public training-graph display hooks. Training graph export is handled internally during train()."
        );
    }

    /**
     * Run ASTRA batch inference across multiple image entries using a single
     * Cellpose invocation for the whole staged corpus.
     * <p>
     * This mirrors the high-level ASTRA training architecture: stage first,
     * execute the Python backend once, then map results back deterministically.
     * The method deliberately does not mutate any image hierarchy; it returns
     * detections grouped by the original parent objects so that ASTRA-side
     * pipelines such as tuning, analysis, or future corpus-level workflows can
     * score or persist results without side effects.
     */

    public List<BatchInferenceResult> runBatchInference(List<BatchInferenceRequest> requests) throws IOException, InterruptedException {
        requireExecutionModelReference(this);
        Objects.requireNonNull(requests, "requests");
        if (!batchInferenceEnabled) {
            throw new IllegalStateException("ASTRA batch inference was requested while batchInferenceEnabled=false.");
        }
        clearLastCanonicalPixelSizeUsed();
        if (requests.isEmpty()) {
            return List.of();
        }

        File batchTempDirectory = requireConfiguredTempDirectory();
        resetDirectory(batchTempDirectory);

        List<BatchEntryContext> contexts = buildBatchContexts(requests);
        List<TileFile> allTiles = stageBatchTiles(contexts, batchTempDirectory);

        if (allTiles.isEmpty()) {
            return buildEmptyBatchResults(contexts);
        }

        runCellposeBatch(batchTempDirectory, allTiles);
        return collectBatchResults(contexts, allTiles);
    }


    public List<BatchInferenceResult> runBatchInference(BatchInferenceRequest... requests) throws IOException, InterruptedException {
        Objects.requireNonNull(requests, "requests");
        return runBatchInference(Arrays.asList(requests));
    }


    private void generateValidationPredictions() {
        String executionModelReference = resolveExecutionModelReference(this);
        String executionModelDisplayName = resolveExecutionModelDisplayName(this);

        logger.info("Running the model {} on the validation images to obtain validation predictions", executionModelDisplayName);

        try {
            runCellposeInDirectory(getValidationDirectory(), executionModelReference, null);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("ASTRA validation prediction on validation images failed.", e);
        }
    }

    private ResultsTable computeValidationMetricsWithHelper() throws IOException, InterruptedException {
        File validationResultsDirectory = getValidationResultsDirectory();
        ensureDirectoryExists(validationResultsDirectory);

        File extensionRoot = resolveInstalledExtensionRoot();
        File validationMetricsHelperFile = resolveValidationMetricsHelperFile(extensionRoot);
        if (!validationMetricsHelperFile.isFile()) {
            throw new IOException("ASTRA validation metrics helper script was not found: " + validationMetricsHelperFile.getAbsolutePath());
        }

        VirtualEnvironmentRunner validationRunner = createRuntimeRunner();
        String executionModelDisplayName = resolveExecutionModelDisplayName(this);

        List<String> validationArguments = new ArrayList<>(Arrays.asList(
                validationMetricsHelperFile.getAbsolutePath(),
                getValidationDirectory().getAbsolutePath(),
                executionModelDisplayName,
                validationResultsDirectory.getAbsolutePath()
        ));

        validationRunner.setArguments(validationArguments);
        validationRunner.runCommand(true);
        requireSuccessfulProcessExit(validationRunner, "ASTRA validation metrics helper");

        File validationResultsFile = resolveValidationResultsFile(validationResultsDirectory);
        if (!validationResultsFile.isFile()) {
            throw new IOException("ASTRA validation results file was not produced: " + validationResultsFile.getAbsolutePath());
        }

        return ResultsTable.open(validationResultsFile.getAbsolutePath());
    }

    private ResultsTable parseTrainingResults() throws IOException {
        ResultsTable trainingResults = new ResultsTable();
        List<String> logLines = getOutputLog();

        if (logLines != null) {
            for (String line : logLines) {
                for (Cellpose2D.LogParser parser : Cellpose2D.LogParser.values()) {
                    var matcher = parser.getPattern().matcher(line);
                    if (matcher.find()) {
                        trainingResults.incrementCounter();
                        trainingResults.addValue("Epoch", Double.parseDouble(matcher.group("epoch")));
                        trainingResults.addValue("Time", Double.parseDouble(matcher.group("time")));
                        trainingResults.addValue("Loss", Double.parseDouble(matcher.group("loss")));
                        if (parser != Cellpose2D.LogParser.OMNI) {
                            trainingResults.addValue("Validation Loss", Double.parseDouble(matcher.group("val")));
                            trainingResults.addValue("LR", Double.parseDouble(matcher.group("lr")));
                        } else {
                            trainingResults.addValue("Validation Loss", Double.NaN);
                            trainingResults.addValue("LR", Double.NaN);
                        }
                    }
                }
            }
        }

        File trainingResultsFile = resolveTrainingResultsFile(getTrainingResultsDirectory());
        logger.info("Saving Training Results to {}", trainingResultsFile.getAbsolutePath());
        trainingResults.save(trainingResultsFile.getAbsolutePath());
        return trainingResults;
    }


    private LineChart<Number, Number> createTrainingLossChart(ResultsTable trainingResults) {
        NumberAxis epochAxis = createEpochAxis(trainingResults);
        NumberAxis lossAxis = createLossAxis(trainingResults);

        LineChart<Number, Number> chart = new LineChart<>(epochAxis, lossAxis);
        chart.setTitle("ASTRA Cellpose Training");
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(true);
        chart.setAlternativeColumnFillVisible(false);
        chart.setAlternativeRowFillVisible(false);
        chart.setHorizontalGridLinesVisible(true);
        chart.setVerticalGridLinesVisible(true);
        chart.setPrefSize(1600, 900);
        chart.setMinSize(1600, 900);
        chart.setStyle("-fx-background-color: white; -fx-font-size: 14px;");

        chart.getData().add(createLossSeries(trainingResults, "Training Loss", "Loss"));

        XYChart.Series<Number, Number> validationLossSeries = createLossSeries(trainingResults, "Validation Loss", "Validation Loss");
        if (!validationLossSeries.getData().isEmpty()) {
            chart.getData().add(validationLossSeries);
        }

        return chart;
    }

    private static NumberAxis createEpochAxis(ResultsTable trainingResults) {
        double minimumEpoch = Double.POSITIVE_INFINITY;
        double maximumEpoch = Double.NEGATIVE_INFINITY;

        for (int rowIndex = 0; rowIndex < trainingResults.getCounter(); rowIndex++) {
            double epoch = trainingResults.getValue("Epoch", rowIndex);
            if (Double.isFinite(epoch)) {
                minimumEpoch = Math.min(minimumEpoch, epoch);
                maximumEpoch = Math.max(maximumEpoch, epoch);
            }
        }

        if (!Double.isFinite(minimumEpoch) || !Double.isFinite(maximumEpoch)) {
            minimumEpoch = 0.0;
            maximumEpoch = 1.0;
        }

        if (maximumEpoch <= minimumEpoch) {
            maximumEpoch = minimumEpoch + 1.0;
        }

        NumberAxis axis = new NumberAxis(minimumEpoch, maximumEpoch, Math.max(1.0, niceTickUnit(maximumEpoch - minimumEpoch)));
        axis.setLabel("Epoch");
        axis.setMinorTickVisible(false);
        axis.setAutoRanging(false);
        return axis;
    }

    private static NumberAxis createLossAxis(ResultsTable trainingResults) {
        double minimumLoss = Double.POSITIVE_INFINITY;
        double maximumLoss = Double.NEGATIVE_INFINITY;

        for (int rowIndex = 0; rowIndex < trainingResults.getCounter(); rowIndex++) {
            minimumLoss = updateMinimum(minimumLoss, trainingResults.getValue("Loss", rowIndex));
            minimumLoss = updateMinimum(minimumLoss, trainingResults.getValue("Validation Loss", rowIndex));
            maximumLoss = updateMaximum(maximumLoss, trainingResults.getValue("Loss", rowIndex));
            maximumLoss = updateMaximum(maximumLoss, trainingResults.getValue("Validation Loss", rowIndex));
        }

        if (!Double.isFinite(minimumLoss) || !Double.isFinite(maximumLoss)) {
            minimumLoss = 0.0;
            maximumLoss = 1.0;
        }

        double span = maximumLoss - minimumLoss;
        double padding = span > 0.0 ? span * 0.08 : Math.max(0.1, maximumLoss * 0.1);
        double lowerBound = Math.max(0.0, minimumLoss - padding);
        double upperBound = maximumLoss + padding;
        if (upperBound <= lowerBound) {
            upperBound = lowerBound + 1.0;
        }

        NumberAxis axis = new NumberAxis(lowerBound, upperBound, niceTickUnit(upperBound - lowerBound));
        axis.setLabel("Loss");
        axis.setForceZeroInRange(lowerBound <= 0.0);
        axis.setMinorTickVisible(false);
        axis.setAutoRanging(false);
        return axis;
    }

    private static XYChart.Series<Number, Number> createLossSeries(ResultsTable trainingResults, String seriesName, String columnName) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(seriesName);

        for (int rowIndex = 0; rowIndex < trainingResults.getCounter(); rowIndex++) {
            double epoch = trainingResults.getValue("Epoch", rowIndex);
            double value = trainingResults.getValue(columnName, rowIndex);
            if (Double.isFinite(epoch) && Double.isFinite(value)) {
                series.getData().add(new XYChart.Data<>(epoch, value));
            }
        }

        return series;
    }

    private static double updateMinimum(double currentMinimum, double candidate) {
        return Double.isFinite(candidate) ? Math.min(currentMinimum, candidate) : currentMinimum;
    }

    private static double updateMaximum(double currentMaximum, double candidate) {
        return Double.isFinite(candidate) ? Math.max(currentMaximum, candidate) : currentMaximum;
    }

    private static double niceTickUnit(double span) {
        if (!Double.isFinite(span) || span <= 0.0) {
            return 0.1;
        }

        double rawTick = span / 8.0;
        double magnitude = Math.pow(10.0, Math.floor(Math.log10(rawTick)));
        double normalized = rawTick / magnitude;

        double niceNormalized;
        if (normalized <= 1.0) {
            niceNormalized = 1.0;
        } else if (normalized <= 2.0) {
            niceNormalized = 2.0;
        } else if (normalized <= 5.0) {
            niceNormalized = 5.0;
        } else {
            niceNormalized = 10.0;
        }

        return niceNormalized * magnitude;
    }

    private static WritableImage snapshotChart(LineChart<Number, Number> chart) {
        chart.applyCss();
        chart.layout();

        SnapshotParameters snapshotParameters = new SnapshotParameters();
        snapshotParameters.setFill(Color.WHITE);
        snapshotParameters.setTransform(Transform.scale(2.0, 2.0));

        int imageWidth = (int) Math.round(chart.getPrefWidth() * 2.0);
        int imageHeight = (int) Math.round(chart.getPrefHeight() * 2.0);
        return chart.snapshot(snapshotParameters, new WritableImage(imageWidth, imageHeight));
    }

    private static void saveTrainingChart(LineChart<Number, Number> chart, File outputFile) throws IOException {
        new Scene(new Group(chart));
        WritableImage writableImage = snapshotChart(chart);
        RenderedImage renderedImage = SwingFXUtils.fromFXImage(writableImage, null);
        if (!ImageIO.write(renderedImage, "png", outputFile)) {
            throw new IOException("Could not write training graph image: no PNG writer available.");
        }
    }

    private void saveTrainingGraphAfterTraining() throws IOException {
        ResultsTable trainingResults = getTrainingResults();
        if (trainingResults == null) {
            throw new IllegalStateException("ASTRA training graph save requires parsed training results.");
        }

        File trainingGraphFile = resolveTrainingGraphFile(getTrainingResultsDirectory());

        RuntimeException[] runtimeError = new RuntimeException[1];
        IOException[] ioError = new IOException[1];
        CountDownLatch latch = new CountDownLatch(1);

        FXUtils.runOnApplicationThread(() -> {
            try {
                LineChart<Number, Number> exportChart = createTrainingLossChart(trainingResults);
                saveTrainingChart(exportChart, trainingGraphFile);
            } catch (IOException e) {
                ioError[0] = e;
            } catch (RuntimeException e) {
                runtimeError[0] = e;
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IOException("Timed out while saving ASTRA training graph.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving ASTRA training graph.", e);
        }

        if (runtimeError[0] != null) {
            throw runtimeError[0];
        }
        if (ioError[0] != null) {
            throw ioError[0];
        }
    }

    private List<BatchEntryContext> buildBatchContexts(List<BatchInferenceRequest> requests) {
        List<BatchEntryContext> contexts = new ArrayList<>();
        IdentityHashMap<PathObject, Integer> seenParents = new IdentityHashMap<>();

        for (int i = 0; i < requests.size(); i++) {
            BatchInferenceRequest request = Objects.requireNonNull(requests.get(i), "Batch inference request at index " + i + " is null.");
            String key = request.key() == null || request.key().isBlank()
                    ? String.format("entry_%04d", i)
                    : request.key().trim();

            List<PathObject> parents = new ArrayList<>(request.parents());
            for (PathObject parent : parents) {
                if (parent == null) {
                    throw new IllegalArgumentException("Batch inference request '" + key + "' contains a null parent.");
                }
                Integer previous = seenParents.put(parent, i);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "The same PathObject parent instance was supplied more than once across batch inference requests. " +
                                    "This is ambiguous and not allowed. key='" + key + "'."
                    );
                }
            }

            BatchEntryContext context = new BatchEntryContext(i, key, request.imageData(), List.copyOf(parents));
            contexts.add(context);
        }

        return List.copyOf(contexts);
    }

    private List<BatchInferenceResult> buildEmptyBatchResults(List<BatchEntryContext> contexts) {
        List<BatchInferenceResult> results = new ArrayList<>();
        for (BatchEntryContext context : contexts) {
            List<BatchParentResult> parentResults = new ArrayList<>();
            for (PathObject parent : context.parents()) {
                parentResults.add(new BatchParentResult(parent, List.of()));
            }
            results.add(new BatchInferenceResult(context.key(), context.imageData(), List.copyOf(parentResults)));
        }
        return List.copyOf(results);
    }


    private List<TileFile> stageBatchTiles(List<BatchEntryContext> contexts, File batchTempDirectory) throws IOException, InterruptedException {
        List<TileFile> allTiles = new ArrayList<>();

        for (BatchEntryContext context : contexts) {
            BatchStagingContext stagingContext = createBatchStagingContext(context);

            ImageData<BufferedImage> imageData = context.imageData();
            ImageDataServer<BufferedImage> opServer = stagingContext.opServer();
            ImageServer<BufferedImage> server = stagingContext.server();
            PixelCalibration calibration = stagingContext.nativeCalibration();
            double requestDownsample = stagingContext.requestDownsample();

            context.setServer(server);
            context.setCalibration(calibration);
            context.setFinalDownsample(requestDownsample);
            context.setExpansion(stagingContext.expansion());

            int tileSequence = 0;
            for (PathObject realParent : context.parents()) {
                ROI roi = realParent.getROI();
                int pad = scaledPixelsOrZero(padding, calibration.getAveragedPixelSize().doubleValue());
                ROI paddedRoi = ROIs.createRectangleROI(
                        roi.getBoundsX() - pad,
                        roi.getBoundsY() - pad,
                        roi.getBoundsWidth() + 2 * pad,
                        roi.getBoundsHeight() + 2 * pad
                );

                PathObject paddedParent = PathObjects.createAnnotationObject(paddedRoi);
                RegionRequest request = RegionRequest.createInstance(
                        opServer.getPath(),
                        requestDownsample,
                        paddedParent.getROI()
                );

                Collection<? extends ROI> tiledRois = RoiTools.computeTiledROIs(
                        paddedParent.getROI(),
                        ImmutableDimension.getInstance(
                                scaledDimension(tileWidth, requestDownsample),
                                scaledDimension(tileHeight, requestDownsample)
                        ),
                        ImmutableDimension.getInstance(
                                scaledDimension(tileWidth * 1.5, requestDownsample),
                                scaledDimension(tileHeight * 1.5, requestDownsample)
                        ),
                        true,
                        scaledPixelsOrZero(overlap, requestDownsample)
                );

                List<RegionRequest> tiles = tiledRois.stream()
                        .map((ROI roiTile) -> RegionRequest.createInstance(opServer.getPath(), requestDownsample, roiTile))
                        .collect(Collectors.toList());

                ImageDataOp opWithPreprocessing = buildPreprocessingOp(imageData, paddedParent, request);

                logger.info("ASTRA batch inference staging {} tiles for {} / {}", tiles.size(), context.key(), realParent);
                for (RegionRequest tile : tiles) {
                    TileFile savedTile = saveBatchTileImage(opWithPreprocessing, imageData, tile, realParent, context.index(), tileSequence++, batchTempDirectory);
                    if (savedTile != null) {
                        allTiles.add(savedTile);
                    }
                }
            }
        }

        return List.copyOf(allTiles);
    }


    private ImageDataOp buildPreprocessingOp(ImageData<BufferedImage> imageData, PathObject paddedParent, RegionRequest request) throws IOException, InterruptedException {
        ArrayList<ImageOp> fullPreprocess = new ArrayList<>();
        fullPreprocess.add(ImageOps.Core.ensureType(PixelType.FLOAT32));

        if (globalPreprocess) {
            if (globalPreprocessingProvidedByUser == null) {
                List<ImageOp> splitMergeListImageOp = new ArrayList<>();
                for (var entry : this.normalizeChannelPercentilesGlobalMap.entrySet()) {
                    splitMergeListImageOp.add(createNormalizationImageOp(
                            entry.getKey(),
                            entry.getValue(),
                            imageData,
                            paddedParent.getROI(),
                            request.getImagePlane()
                    ));
                }
                fullPreprocess.add(ImageOps.Core.splitMerge(splitMergeListImageOp));
            } else {
                Object normalizeOps = globalPreprocessingProvidedByUser.createOps(op, imageData, paddedParent.getROI(), request.getImagePlane());
                if (normalizeOps instanceof Collection<?> normalizeOpCollection) {
                    for (Object normalizeOp : normalizeOpCollection) {
                        fullPreprocess.add((ImageOp) normalizeOp);
                    }
                } else if (normalizeOps instanceof ImageOp normalizeOp) {
                    fullPreprocess.add(normalizeOp);
                } else if (normalizeOps != null) {
                    throw new IllegalStateException("Unexpected preprocessing op payload type: " + normalizeOps.getClass().getName());
                }
            }
            this.parameters.put("no_norm", null);
        }

        if (preprocess != null && !preprocess.isEmpty()) {
            fullPreprocess.addAll(preprocess);
        }

        if (fullPreprocess.size() > 1) {
            fullPreprocess.add(ImageOps.Core.ensureType(PixelType.FLOAT32));
        }

        return op.appendOps(fullPreprocess.toArray(ImageOp[]::new));
    }


    private TileFile saveBatchTileImage(
            ImageDataOp opWithPreprocessing,
            ImageData<BufferedImage> imageData,
            RegionRequest request,
            PathObject parent,
            int entryIndex,
            int tileSequence,
            File batchTempDirectory
    ) throws IOException {
        Mat mat = opWithPreprocessing.apply(imageData, request);
        ImagePlus imp = OpenCVTools.matToImagePlus("ASTRA-Batch", mat);

        try {
            String fileName = String.format(
                    "Batch_%04d_%06d_x%d_y%d_z%d_t%d.tif",
                    entryIndex,
                    tileSequence,
                    request.getX(),
                    request.getY(),
                    request.getZ(),
                    request.getT()
            );
            File tempFile = new File(batchTempDirectory, fileName);
            logger.info("ASTRA batch inference saving tile to {}", tempFile);

            if (imp.getWidth() < 10 || imp.getHeight() < 10) {
                logger.warn("ASTRA batch inference tile {} will not be saved because it is too small: {}", tempFile, imp);
                return null;
            }

            IJ.save(imp, tempFile.getAbsolutePath());
            return new TileFile(request, tempFile, parent);
        } finally {
            imp.close();
        }
    }


    private List<BatchInferenceResult> collectBatchResults(List<BatchEntryContext> contexts, List<TileFile> allTiles) {
        IdentityHashMap<PathObject, List<CandidateObject>> rawCandidatesByParent = new IdentityHashMap<>();

        for (TileFile tile : allTiles) {
            PathObject parent = tile.getParent();
            Collection<CandidateObject> candidates = tile.getCandidates();
            if (candidates != null && !candidates.isEmpty()) {
                rawCandidatesByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).addAll(candidates);
            }
        }

        List<BatchInferenceResult> results = new ArrayList<>();
        for (BatchEntryContext context : contexts) {
            List<BatchParentResult> parentResults = new ArrayList<>();
            for (PathObject parent : context.parents()) {
                List<CandidateObject> rawCandidates = rawCandidatesByParent.getOrDefault(parent, Collections.emptyList());
                List<PathObject> detections = finalizeParentDetections(context, parent, rawCandidates);
                parentResults.add(new BatchParentResult(parent, List.copyOf(detections)));
            }
            results.add(new BatchInferenceResult(context.key(), context.imageData(), List.copyOf(parentResults)));
        }

        return List.copyOf(results);
    }

    private List<PathObject> finalizeParentDetections(BatchEntryContext context, PathObject parent, Collection<CandidateObject> rawCandidates) {
        List<CandidateObject> filteredDetections = resolveDetectionOverlaps(rawCandidates);

        Geometry mask = parent.getROI().getGeometry();
        List<PathObject> finalObjects = new ArrayList<>();

        for (CandidateObject candidate : filteredDetections) {
            try {
                PathObject pathObject = createPathObjectFromCandidate(
                        candidate,
                        parent.getROI().getImagePlane(),
                        context.expansion(),
                        constrainToParent,
                        mask
                );
                if (pathObject != null) {
                    finalObjects.add(pathObject);
                }
            } catch (RuntimeException e) {
                logger.warn("ASTRA batch inference failed to convert a candidate object for parent {}: {}", parent, e.getLocalizedMessage(), e);
            }
        }

        if (context.expansion() > 0 && !ignoreCellOverlaps) {
            logger.info("ASTRA batch inference resolving cell overlaps for {}", parent);
            if (creatorFun != null) {
                List<PathObject> cells = finalObjects.stream()
                        .map(AstraCellpose2D::convertObjectToCell)
                        .collect(Collectors.toList());
                cells = CellTools.constrainCellOverlaps(cells);
                finalObjects = cells.stream()
                        .map(cell -> convertCellToObject(cell, creatorFun))
                        .collect(Collectors.toList());
            } else {
                finalObjects = CellTools.constrainCellOverlaps(finalObjects);
            }
        }

        if (measureShape) {
            for (PathObject object : finalObjects) {
                ObjectMeasurements.addShapeMeasurements(object, context.calibration());
            }
        }

        if (!finalObjects.isEmpty() && measurements != null && !measurements.isEmpty()) {
            logger.info("ASTRA batch inference making measurements for {}", parent);
            var stains = context.imageData().getColorDeconvolutionStains();
            var builder = new TransformedServerBuilder(context.server());
            if (stains != null) {
                List<Integer> stainNumbers = new ArrayList<>();
                for (int s = 1; s <= 3; s++) {
                    if (!stains.getStain(s).isResidual()) {
                        stainNumbers.add(s);
                    }
                }
                builder.deconvolveStains(stains, stainNumbers.stream().mapToInt(i -> i).toArray());
            }

            var server2 = builder.build();
            for (PathObject cell : finalObjects) {
                try {
                    ObjectMeasurements.addIntensityMeasurements(server2, cell, context.finalDownsample(), measurements, compartments);
                } catch (IOException e) {
                    logger.info("ASTRA batch inference error adding intensity measurement: {}", e.getLocalizedMessage(), e);
                }
            }
        }

        return finalObjects;
    }


    private Double resolveTrainingCanonicalPixelSizeFromProject() {
        var qupath = QPEx.getQuPath();
        if (qupath == null || qupath.getProject() == null) {
            return null;
        }

        for (var entry : qupath.getProject().getImageList()) {
            try {
                ImageData<BufferedImage> imageData = entry.readImageData();
                if (imageData == null) {
                    continue;
                }

                var hierarchy = imageData.getHierarchy();
                if (hierarchy == null) {
                    continue;
                }

                boolean hasTrainingOrValidation = hierarchy.getAnnotationObjects().stream().anyMatch(annotation -> {
                    if (annotation == null || annotation.getPathClass() == null) {
                        return false;
                    }
                    String pathClass = annotation.getPathClass().toString();
                    return "Training".equals(pathClass) || "Validation".equals(pathClass);
                });
                if (!hasTrainingOrValidation) {
                    continue;
                }

                PixelCalibration calibration = imageData.getServer().getPixelCalibration();
                double pixelSizeValue = requireFinitePositivePixelSize(calibration, "Training image pixel size", entry.getImageName());
                return pixelSizeValue;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("ASTRA training failed to resolve a canonical working pixel size from the project.", e);
            }
        }

        return null;
    }

    private BatchStagingContext createBatchStagingContext(BatchEntryContext context) throws IOException {
        ImageData<BufferedImage> imageData = context.imageData();
        ImageServer<BufferedImage> server = imageData.getServer();
        PixelCalibration nativeCalibration = server.getPixelCalibration();

        double nativePixelSize = requireFinitePositivePixelSize(nativeCalibration, "Native image pixel size", context.key());
        double workingPixelSize = pixelScalingEnabled ? requireCanonicalBatchPixelSize() : nativePixelSize;
        double requestedDownsample = workingPixelSize / nativePixelSize;
        double expansion = cellExpansion / nativePixelSize;

        PixelCalibration workingResolution = nativeCalibration.createScaledInstance(requestedDownsample, requestedDownsample);
        ImageDataServer<BufferedImage> opServer = ImageOps.buildServer(imageData, op, workingResolution, tileWidth, tileHeight);
        double effectiveDownsample = validateCanonicalBatchScaling(context.key(), workingPixelSize, nativePixelSize, requestedDownsample, opServer);

        if (pixelScalingEnabled) {
            if (lastCanonicalPixelSizeUsed == null) {
                lastCanonicalPixelSizeUsed = workingPixelSize;
            } else if (!approximatelyEqual(lastCanonicalPixelSizeUsed, workingPixelSize)) {
                throw new IllegalStateException(
                        "ASTRA batch inference resolved inconsistent canonical pixel sizes across the same batch run. " +
                                "Previous=" + lastCanonicalPixelSizeUsed + ", current=" + workingPixelSize + "."
                );
            }
        }

        return new BatchStagingContext(server, nativeCalibration, opServer, effectiveDownsample, expansion);
    }

    private double requireCanonicalBatchPixelSize() {
        if (!Double.isFinite(pixelSize) || pixelSize <= 0) {
            throw new IllegalStateException(
                    "ASTRA batch inference requires builder.pixelSize(...) to be set explicitly. " +
                            "That value is the canonical working um/px for the staged batch corpus."
            );
        }
        return pixelSize;
    }

    private static double validateCanonicalBatchScaling(
            String key,
            double canonicalPixelSize,
            double nativePixelSize,
            double expectedDownsample,
            ImageDataServer<BufferedImage> opServer
    ) {
        double actualDownsample = opServer.getDownsampleForResolution(0);

        if (!Double.isFinite(actualDownsample) || actualDownsample <= 0) {
            throw new IllegalStateException(
                    "ASTRA batch inference staging produced a non-finite downsample for '" + key + "': " + actualDownsample
            );
        }

        if (!approximatelyEqual(actualDownsample, expectedDownsample)) {
            throw new IllegalStateException(
                    "ASTRA batch inference staging produced the wrong downsample for '" + key + "'. " +
                            "Expected=" + expectedDownsample + ", actual=" + actualDownsample +
                            ", native um/px=" + nativePixelSize + ", canonical um/px=" + canonicalPixelSize + '.'
            );
        }

        double realizedPixelSize = nativePixelSize * actualDownsample;
        if (!Double.isFinite(realizedPixelSize) || realizedPixelSize <= 0) {
            throw new IllegalStateException(
                    "ASTRA batch inference staging produced a non-finite realized pixel size for '" + key + "': " + realizedPixelSize
            );
        }

        if (!approximatelyEqual(realizedPixelSize, canonicalPixelSize)) {
            throw new IllegalStateException(
                    "ASTRA batch inference staging produced the wrong realized effective pixel size for '" + key + "'. " +
                            "Expected canonical um/px=" + canonicalPixelSize + ", realized um/px=" + realizedPixelSize +
                            ", native um/px=" + nativePixelSize + ", downsample=" + actualDownsample + '.'
            );
        }

        return actualDownsample;
    }

    private static boolean approximatelyEqual(double a, double b) {
        double absTolerance = 1e-9;
        double relativeTolerance = 1e-6;
        double scale = Math.max(Math.abs(a), Math.abs(b));
        double tolerance = Math.max(absTolerance, scale * relativeTolerance);
        return Math.abs(a - b) <= tolerance;
    }

    private static double requireFinitePositivePixelSize(PixelCalibration calibration, String label, String key) {
        Objects.requireNonNull(calibration, "calibration");
        Number averagedPixelSize = calibration.getAveragedPixelSize();
        double value = averagedPixelSize == null ? Double.NaN : averagedPixelSize.doubleValue();
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalStateException(label + " must be finite and > 0 for batch entry '" + key + "'. Actual value=" + value);
        }
        return value;
    }

    private static int scaledDimension(double basePixels, double downsample) {
        return Math.max(1, (int)Math.round(basePixels * downsample));
    }

    private static int scaledPixelsOrZero(double basePixels, double scale) {
        return Math.max(0, (int)Math.round(basePixels * scale));
    }

    private ImageOp createNormalizationImageOp(
            ColorTransforms.ColorTransform channel,
            Map<String, Double> attributes,
            ImageData<BufferedImage> imageData,
            ROI parentROI,
            ImagePlane imagePlane
    ) throws IOException {
        ImageDataOp channelOp = ImageOps.buildImageDataOp(channel);
        double percentileMin = attributes.get("percentileMin");
        double percentileMax = attributes.get("percentileMax");
        double normDownsample = attributes.get("normDownsample");
        double index = attributes.get("index");

        OpCreators.TileOpCreator normOp = new OpCreators.ImageNormalizationBuilder()
                .percentiles(percentileMin, percentileMax)
                .perChannel(true)
                .downsample(normDownsample)
                .useMask(true)
                .build();

        var normalizeOpsTmp = normOp.createOps(channelOp, imageData, parentROI, imagePlane);
        List<ImageOp> normalizeOps = new ArrayList<>(normalizeOpsTmp);
        normalizeOps.add(0, ImageOps.Channels.extract((int)index));
        return ImageOps.Core.sequential(normalizeOps);
    }

    private void resetDirectory(File directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        try {
            if (directory.exists()) {
                FileUtils.deleteDirectory(directory);
            }
            FileUtils.forceMkdir(directory);
        } catch (IOException e) {
            throw new IOException("Failed to reset directory: " + directory.getAbsolutePath(), e);
        }
    }

    private void runTrainingCommand() throws IOException, InterruptedException {
        VirtualEnvironmentRunner veRunner = createRuntimeRunner();

        List<String> cellposeArguments = new ArrayList<>(Arrays.asList("-Xutf8", "-W", "ignore", "-m", "cellpose"));
        cellposeArguments.add("--train");
        cellposeArguments.add("--dir");
        cellposeArguments.add(getTrainingDirectory().getAbsolutePath());

        if (this.useTestDir) {
            cellposeArguments.add("--test_dir");
            cellposeArguments.add(getValidationDirectory().getAbsolutePath());
        }

        cellposeArguments.add("--pretrained_model");
        cellposeArguments.add(Objects.requireNonNullElse(model, "None"));

        this.parameters.forEach((parameter, value) -> {
            cellposeArguments.add("--" + parameter);
            if (value != null) {
                cellposeArguments.add(value);
            }
        });

        if (!this.disableGPU) {
            cellposeArguments.add("--use_gpu");
        }

        cellposeArguments.add("--verbose");

        veRunner.setArguments(cellposeArguments);
        veRunner.runCommand(true);
        requireSuccessfulProcessExit(veRunner, "ASTRA training process");
        writeCellpose2DField("theLog", veRunner.getProcessLog());
    }

    private VirtualEnvironmentRunner createRuntimeRunner() {
        requireSupportedRuntimeConfiguration();

        String pythonPath = cellposeSetup.getCellposePythonPath();
        if (pythonPath == null || pythonPath.isBlank()) {
            throw new IllegalStateException(
                    "ASTRA runtime Python path is empty. Please configure it in Edit > Preferences."
            );
        }

        File pythonExecutable = new File(pythonPath.trim());
        if (!pythonExecutable.exists() || !pythonExecutable.isFile()) {
            throw new IllegalStateException(
                    "ASTRA runtime Python path does not resolve to an executable file: " + pythonExecutable.getAbsolutePath()
            );
        }

        return new VirtualEnvironmentRunner(
                pythonExecutable.getAbsolutePath(),
                VirtualEnvironmentRunner.EnvType.EXE,
                null,
                this.getClass().getSimpleName()
        );
    }

    private void requireSupportedRuntimeConfiguration() {
        if (useCellposeSAM) {
            throw new IllegalStateException(
                    "ASTRA does not support useCellposeSAM(). Remove that selector and use the single ASTRA runtime path."
            );
        }
        if (parameters.containsKey("omni")) {
            throw new IllegalStateException(
                    "ASTRA does not support Omnipose runtime selection. Remove useOmnipose() or the '--omni' parameter."
            );
        }
    }

    private void runCellposeBatch(File batchDirectory, List<TileFile> allTiles) throws IOException, InterruptedException {
        runCellposeInDirectory(batchDirectory, resolveExecutionModelReference(this), allTiles);
    }

    private void runCellposeInDirectory(File inputDirectory, String executionModelReference, List<TileFile> allTiles) throws IOException, InterruptedException {
        VirtualEnvironmentRunner veRunner = createRuntimeRunner();

        List<String> cellposeArguments = new ArrayList<>(Arrays.asList("-Xutf8", "-W", "ignore", "-m", "cellpose"));
        cellposeArguments.add("--dir");
        cellposeArguments.add(inputDirectory.getAbsolutePath());
        cellposeArguments.add("--pretrained_model");
        // Unified model handoff: this single argument accepts either a promoted model path
        // or a shipped model name resolved upstream by the Groovy stack.
        cellposeArguments.add(executionModelReference);

        this.parameters.forEach((parameter, value) -> {
            cellposeArguments.add("--" + parameter);
            if (value != null) {
                cellposeArguments.add(value);
            }
        });

        cellposeArguments.add("--save_tif");
        cellposeArguments.add("--no_npy");

        if (!this.disableGPU) {
            cellposeArguments.add("--use_gpu");
        }

        cellposeArguments.add("--verbose");

        veRunner.setArguments(cellposeArguments);
        veRunner.runCommand(false);
        processCellposeOutputFiles(veRunner, inputDirectory, allTiles);
    }

    private void processCellposeOutputFiles(VirtualEnvironmentRunner veRunner, File inputDirectory, List<TileFile> allTiles) throws CancellationException, InterruptedException, IOException {
        if (allTiles == null) {
            requireSuccessfulProcessExit(veRunner, "Cellpose process");
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(determineTileReadThreadCount());
        List<Future<?>> tileReadTasks = new ArrayList<>();

        try {
            if (!this.doReadResultsAsynchronously) {
                requireSuccessfulProcessExit(veRunner, "Cellpose process");
                allTiles.forEach(entry -> tileReadTasks.add(submitTileReadTask(executor, entry)));
            } else {
                LinkedHashMap<File, TileFile> remainingFiles = allTiles.stream()
                        .map(entry -> new AbstractMap.SimpleEntry<>(entry.getLabelFile(), entry))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));
                boolean processCompletedSuccessfully = false;

                try {
                    veRunner.startWatchService(inputDirectory.toPath());

                    while (!remainingFiles.isEmpty() && veRunner.getProcess().isAlive()) {
                        List<String> changedFiles = veRunner.getChangedFiles();
                        if (changedFiles.isEmpty()) {
                            continue;
                        }

                        LinkedHashMap<File, TileFile> finishedFiles = remainingFiles.entrySet().stream()
                                .filter(set -> changedFiles.contains(set.getKey().getName()))
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));

                        finishedFiles.forEach((key, tile) -> tileReadTasks.add(submitTileReadTask(executor, tile)));
                        finishedFiles.forEach((k, v) -> remainingFiles.remove(k));
                    }

                    requireSuccessfulProcessExit(veRunner, "Cellpose process");
                    processCompletedSuccessfully = true;
                } finally {
                    if (processCompletedSuccessfully) {
                        List<String> changedFiles = veRunner.getChangedFiles();
                        LinkedHashMap<File, TileFile> finishedFiles = remainingFiles.entrySet().stream()
                                .filter(set -> changedFiles.contains(set.getKey().getName()))
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));

                        finishedFiles.forEach((key, tile) -> tileReadTasks.add(submitTileReadTask(executor, tile)));
                    }
                    veRunner.closeWatchService();
                }
            }

            awaitTileReadTasks(tileReadTasks, "Cellpose tile-read tasks");
        } finally {
            executor.shutdown();
            awaitExecutorTermination(executor, "Cellpose tile-read executor");
        }
    }

    private Collection<CandidateObject> readObjectsFromTileFile(TileFile tileFile) {
        RegionRequest request = tileFile.getTile();

        logger.info("Reading {}", tileFile.getLabelFile().getName());
        ImagePlus labelImp = IJ.openImage(tileFile.getLabelFile().getAbsolutePath());
        if (labelImp == null) {
            throw new IllegalStateException("Could not open Cellpose label image: " + tileFile.getLabelFile().getAbsolutePath());
        }

        ImageProcessor ip = labelImp.getProcessor();
        Wand wand = new Wand(ip);

        int width = ip.getWidth();
        int height = ip.getHeight();

        ip.setColor(0);
        List<CandidateObject> candidateObjects = new ArrayList<>();

        for (int yCoordinate = 0; yCoordinate < height; yCoordinate++) {
            for (int xCoordinate = 0; xCoordinate < width; xCoordinate++) {
                float val = ip.getf(xCoordinate, yCoordinate);
                if (val > 0.0) {
                    wand.autoOutline(xCoordinate, yCoordinate, val, val);
                    if (wand.npoints > 0) {
                        Roi roi = new PolygonRoi(wand.xpoints, wand.ypoints, wand.npoints, Roi.FREEROI);
                        Geometry geometry = IJTools.convertToROI(
                                roi,
                                -1.0 * request.getX() / request.getDownsample(),
                                -1.0 * request.getY() / request.getDownsample(),
                                request.getDownsample(),
                                request.getImagePlane()
                        ).getGeometry();
                        candidateObjects.add(new CandidateObject(geometry));
                        ip.fill(roi);
                    }
                }
            }
        }

        labelImp.close();
        return candidateObjects;
    }

    private Geometry simplifyGeometry(Geometry geom) {
        if (simplifyDistance <= 0) {
            return geom;
        }
        try {
            return VWSimplifier.simplify(geom, simplifyDistance);
        } catch (Exception e) {
            return geom;
        }
    }

    private PathObject createPathObjectFromCandidate(CandidateObject object, ImagePlane plane, double cellExpansion, boolean constrainToParent, Geometry mask) {
        var geomNucleus = simplifyGeometry(object.geometry());
        PathObject pathObject;
        if (cellExpansion > 0) {
            var geomCell = CellTools.estimateCellBoundary(geomNucleus, cellExpansion, cellConstrainScale);
            if (constrainToParent) {
                geomCell = GeometryTools.attemptOperation(geomCell, g -> g.intersection(mask));
                var geomCell2 = geomCell;
                geomNucleus = GeometryTools.attemptOperation(geomNucleus, g -> g.intersection(geomCell2));
                geomNucleus = GeometryTools.ensurePolygonal(geomNucleus);
            } else if (!geomNucleus.intersects(mask)) {
                return null;
            }

            geomCell = simplifyGeometry(geomCell);
            geomCell = GeometryTools.ensurePolygonal(geomCell);

            if (geomCell.isEmpty()) {
                logger.warn("Empty cell boundary at {} will be skipped", object.geometry().getCentroid());
                return null;
            }
            if (geomNucleus.isEmpty()) {
                logger.warn("Empty nucleus at {} will be skipped", object.geometry().getCentroid());
                return null;
            }

            var roiCell = GeometryTools.geometryToROI(geomCell, plane);
            var roiNucleus = GeometryTools.geometryToROI(geomNucleus, plane);
            if (creatorFun == null) {
                pathObject = PathObjects.createCellObject(roiCell, roiNucleus, null, null);
            } else {
                pathObject = creatorFun.apply(roiCell);
                if (roiNucleus != null) {
                    pathObject.addChildObject(creatorFun.apply(roiNucleus));
                }
            }
        } else {
            if (constrainToParent) {
                geomNucleus = GeometryTools.attemptOperation(geomNucleus, g -> g.intersection(mask));
                geomNucleus = GeometryTools.ensurePolygonal(geomNucleus);
                if (geomNucleus.isEmpty()) {
                    return null;
                }
            } else if (!geomNucleus.intersects(mask)) {
                return null;
            }

            var roiNucleus = GeometryTools.geometryToROI(geomNucleus, plane);
            if (creatorFun == null) {
                pathObject = PathObjects.createDetectionObject(roiNucleus);
            } else {
                pathObject = creatorFun.apply(roiNucleus);
            }
        }

        var pathClass = globalPathClass;
        if (pathClass != null && pathClass.isValid()) {
            pathObject.setPathClass(pathClass);
        }
        return pathObject;
    }

    private List<CandidateObject> resolveDetectionOverlaps(Collection<CandidateObject> rawCandidates) {
        List<CandidateObject> candidateList = new ArrayList<>(rawCandidates);
        candidateList.sort(Comparator.comparingDouble(o -> -1 * o.area()));

        var retainedObjects = new LinkedHashSet<CandidateObject>();
        var skippedObjects = new HashSet<CandidateObject>();
        int skipErrorCount = 0;

        Map<CandidateObject, Envelope> envelopes = new HashMap<>();
        var tree = new STRtree();
        for (var det : candidateList) {
            var env = det.geometry().getEnvelopeInternal();
            envelopes.put(det, env);
            tree.insert(env, det);
        }

        for (CandidateObject currentCandidate : candidateList) {
            if (skippedObjects.contains(currentCandidate)) {
                continue;
            }

            retainedObjects.add(currentCandidate);
            var envelope = envelopes.get(currentCandidate);

            @SuppressWarnings("unchecked")
            List<CandidateObject> overlaps = (List<CandidateObject>)tree.query(envelope);
            for (CandidateObject overlappingCandidate : overlaps) {
                if (overlappingCandidate == currentCandidate || skippedObjects.contains(overlappingCandidate) || retainedObjects.contains(overlappingCandidate)) {
                    continue;
                }

                try {
                    var env = envelopes.get(overlappingCandidate);
                    if (envelope.intersects(env) && currentCandidate.geometry().intersects(overlappingCandidate.geometry())) {
                        var difference = overlappingCandidate.geometry().difference(currentCandidate.geometry());

                        if (difference instanceof GeometryCollection) {
                            difference = GeometryTools.ensurePolygonal(difference);

                            double maxArea = -1;
                            int index = -1;
                            for (int i = 0; i < difference.getNumGeometries(); i++) {
                                double area = difference.getGeometryN(i).getArea();
                                if (area > maxArea) {
                                    maxArea = area;
                                    index = i;
                                }
                            }
                            if (index < 0) {
                                skippedObjects.add(overlappingCandidate);
                                continue;
                            }
                            difference = difference.getGeometryN(index);
                        }

                        if (difference.getArea() > overlappingCandidate.area() / 2.0) {
                            overlappingCandidate.setGeometry(difference);
                        } else {
                            skippedObjects.add(overlappingCandidate);
                        }
                    }
                } catch (Exception e) {
                    skipErrorCount++;
                    skippedObjects.add(overlappingCandidate);
                }
            }
        }

        if (skipErrorCount > 0) {
            int skipCount = skippedObjects.size();
            logger.warn("Skipped {} object(s) due to error in resolving overlaps ({}% of all skipped)",
                    skipErrorCount, GeneralTools.formatNumber(skipErrorCount * 100.0 / skipCount, 1));
        }

        return new ArrayList<>(retainedObjects);
    }

    private static PathObject convertObjectToCell(PathObject pathObject) {
        ROI roiNucleus = null;
        var children = pathObject.getChildObjects();
        if (children.size() == 1) {
            roiNucleus = children.iterator().next().getROI();
        } else if (children.size() > 1) {
            throw new IllegalArgumentException("Cannot convert object with multiple child objects to a cell!");
        }
        return PathObjects.createCellObject(pathObject.getROI(), roiNucleus, pathObject.getPathClass(), pathObject.getMeasurementList());
    }

    private static PathObject convertCellToObject(PathObject cell, java.util.function.Function<ROI, PathObject> creator) {
        var parent = creator.apply(cell.getROI());
        var nucleusROI = cell instanceof PathCellObject ? ((PathCellObject) cell).getNucleusROI() : null;
        if (nucleusROI != null) {
            var nucleus = creator.apply(nucleusROI);
            nucleus.setPathClass(cell.getPathClass());
            parent.addChildObject(nucleus);
        }
        parent.setPathClass(cell.getPathClass());
        var cellMeasurements = cell.getMeasurementList();
        if (!cellMeasurements.isEmpty()) {
            try (var ml = parent.getMeasurementList()) {
                ml.putAll(cellMeasurements);
            }
        }
        return parent;
    }

    private File requireConfiguredTempDirectory() throws IOException {
        File directory = (File)readCellpose2DField("tempDirectory");
        if (directory == null) {
            throw new IOException("ASTRA batch inference requires tempDirectory to be configured on the builder.");
        }
        return ensureDirectoryExists(directory);
    }

    private void setTempDirectoryField(File tempDirectory) {
        writeCellpose2DField("tempDirectory", tempDirectory);
    }

    private Object readCellpose2DField(String fieldName) {
        try {
            Field field = Cellpose2D.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(this);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read Cellpose2D field '" + fieldName + "'.", e);
        }
    }

    private void writeCellpose2DField(String fieldName, Object value) {
        try {
            Field field = Cellpose2D.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(this, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to write Cellpose2D field '" + fieldName + "'.", e);
        }
    }

    private static final class BatchStagingContext {
        private final ImageServer<BufferedImage> server;
        private final PixelCalibration nativeCalibration;
        private final ImageDataServer<BufferedImage> opServer;
        private final double requestDownsample;
        private final double expansion;
        private BatchStagingContext(
                ImageServer<BufferedImage> server,
                PixelCalibration nativeCalibration,
                ImageDataServer<BufferedImage> opServer,
                double requestDownsample,
                double expansion
        ) {
            this.server = server;
            this.nativeCalibration = nativeCalibration;
            this.opServer = opServer;
            this.requestDownsample = requestDownsample;
            this.expansion = expansion;
        }

        public ImageServer<BufferedImage> server() {
            return server;
        }

        public PixelCalibration nativeCalibration() {
            return nativeCalibration;
        }

        public ImageDataServer<BufferedImage> opServer() {
            return opServer;
        }

        public double requestDownsample() {
            return requestDownsample;
        }

        public double expansion() {
            return expansion;
        }

    }

    private static final class TileFile {
        private final RegionRequest request;
        private final File imageFile;
        private final PathObject parent;
        private Collection<CandidateObject> candidates = Collections.emptyList();

        private TileFile(RegionRequest request, File imageFile, PathObject parent) {
            this.request = request;
            this.imageFile = imageFile;
            this.parent = parent;
        }

        public File getLabelFile() {
            return new File(FilenameUtils.removeExtension(imageFile.getAbsolutePath()) + "_cp_masks.tif");
        }

        public RegionRequest getTile() {
            return request;
        }

        public PathObject getParent() {
            return parent;
        }

        public Collection<CandidateObject> getCandidates() {
            return candidates;
        }

        public void setCandidates(Collection<CandidateObject> candidates) {
            this.candidates = candidates == null ? Collections.emptyList() : candidates;
        }
    }

    private static final class CandidateObject {
        private Geometry geometry;

        private CandidateObject(Geometry geometry) {
            setGeometry(geometry);
        }

        public double area() {
            return geometry.getArea();
        }

        public Geometry geometry() {
            return geometry;
        }

        public void setGeometry(Geometry geometry) {
            Objects.requireNonNull(geometry, "geometry");
            this.geometry = selectLargestPolygonalGeometry(GeometryTools.ensurePolygonal(geometry));
        }

        private static Geometry selectLargestPolygonalGeometry(Geometry geometry) {
            double maxArea = -1.0;
            int index = -1;
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                double area = geometry.getGeometryN(i).getArea();
                if (area > maxArea) {
                    maxArea = area;
                    index = i;
                }
            }
            if (index < 0) {
                throw new IllegalArgumentException("Candidate geometry contains no polygonal components.");
            }
            return geometry.getGeometryN(index);
        }
    }

    public record BatchInferenceRequest(String key, ImageData<BufferedImage> imageData, Collection<? extends PathObject> parents) {
        public BatchInferenceRequest {
            Objects.requireNonNull(imageData, "imageData");
            Objects.requireNonNull(parents, "parents");
            parents = List.copyOf(parents);
        }

        public BatchInferenceRequest(ImageData<BufferedImage> imageData, Collection<? extends PathObject> parents) {
            this(null, imageData, parents);
        }
    }

    public record BatchParentResult(PathObject parent, List<PathObject> detections) {
        public BatchParentResult {
            Objects.requireNonNull(parent, "parent");
            detections = detections == null ? List.of() : List.copyOf(detections);
        }
    }

    public record BatchInferenceResult(String key, ImageData<BufferedImage> imageData, List<BatchParentResult> parentResults) {
        public BatchInferenceResult {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(imageData, "imageData");
            parentResults = parentResults == null ? List.of() : List.copyOf(parentResults);
        }

        public Map<PathObject, List<PathObject>> byParent() {
            Map<PathObject, List<PathObject>> out = new LinkedHashMap<>();
            for (BatchParentResult parentResult : parentResults) {
                out.put(parentResult.parent(), parentResult.detections());
            }
            return Collections.unmodifiableMap(out);
        }
    }



    private static final class BatchEntryContext {
        private final int index;
        private final String key;
        private final ImageData<BufferedImage> imageData;
        private final List<PathObject> parents;
        private ImageServer<BufferedImage> server;
        private PixelCalibration calibration;
        private double finalDownsample;
        private double expansion;

        private BatchEntryContext(int index, String key, ImageData<BufferedImage> imageData, List<PathObject> parents) {
            this.index = index;
            this.key = key;
            this.imageData = imageData;
            this.parents = parents;
        }

        public int index() {
            return index;
        }

        public String key() {
            return key;
        }

        public ImageData<BufferedImage> imageData() {
            return imageData;
        }

        public List<PathObject> parents() {
            return parents;
        }

        public ImageServer<BufferedImage> server() {
            return server;
        }

        public void setServer(ImageServer<BufferedImage> server) {
            this.server = server;
        }

        public PixelCalibration calibration() {
            return calibration;
        }

        public void setCalibration(PixelCalibration calibration) {
            this.calibration = calibration;
        }

        public double finalDownsample() {
            return finalDownsample;
        }

        public void setFinalDownsample(double finalDownsample) {
            this.finalDownsample = finalDownsample;
        }

        public double expansion() {
            return expansion;
        }

        public void setExpansion(double expansion) {
            this.expansion = expansion;
        }
    }



    private void requireValidationInputDirectory() throws IOException {
        if (validationDirectory == null) {
            throw new IOException("ASTRA validation requires the validation input directory to be set by the ASTRA builder.");
        }
        if (!validationDirectory.exists() || !validationDirectory.isDirectory()) {
            throw new IOException("ASTRA validation requires the validation input directory to exist as a directory: " + validationDirectory.getAbsolutePath());
        }
    }

    private File getValidationResultsDirectory() {
        return resolveValidationResultsFolder(resultsDirectory);
    }

    private File getTrainingResultsDirectory() {
        return resolveTrainingResultsFolder(resultsDirectory);
    }





    private void setTrainingResults(ResultsTable resultsTable) {
        writeCellpose2DField("trainingResults", resultsTable);
    }

    private void setValidationResults(ResultsTable resultsTable) {
        this.validationResults = resultsTable;
    }

    private static void copyCellpose2DState(Cellpose2D source, Cellpose2D target) {
        for (Field field : Cellpose2D.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.set(target, field.get(source));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to copy Cellpose2D field '" + field.getName() + "'.", e);
            }
        }
    }



    private static File requireNonNullDirectory(File directory, String message) {
        if (directory == null) {
            throw new IllegalStateException(message);
        }
        return directory;
    }

    private static File ensureSubdirectoryExists(File parent, String childName) {
        File child = new File(parent, childName);
        if (!child.exists() && !child.mkdirs()) {
            throw new IllegalStateException("Could not create directory: " + child.getAbsolutePath());
        }
        if (!child.isDirectory()) {
            throw new IllegalStateException("Expected a directory but found a non-directory path: " + child.getAbsolutePath());
        }
        return child;
    }

    private static boolean isUpstreamDefaultTrainingDirectory(File directory) {
        return directory != null && "cellpose-training".equals(directory.getName());
    }

    private static String explicitExecutionModelReference(AstraCellpose2D cp) {
        String executionModelReference = cp.model;
        return executionModelReference != null && !executionModelReference.isBlank() ? executionModelReference.trim() : null;
    }

    private static String basename(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash < value.length() - 1) {
            return value.substring(slash + 1);
        }
        return value;
    }

    static File resolveModelDirectory(File rootDirectory, File modelDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        return modelDirectory != null ? modelDirectory : new File(rootDirectory, "models");
    }

    static File resolveTrainingRootDirectory(File rootDirectory, File trainingDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        if (trainingDirectory == null || isUpstreamDefaultTrainingDirectory(trainingDirectory)) {
            return new File(rootDirectory, "training");
        }
        return trainingDirectory;
    }

    static File resolveValidationInputDirectory(File rootDirectory, File validationDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        return validationDirectory != null ? validationDirectory : new File(rootDirectory, "validation");
    }

    static File resolveResultsDirectory(File rootDirectory, File resultsDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        return resultsDirectory != null ? resultsDirectory : new File(rootDirectory, "results");
    }

    static File ensureDirectoryExists(File directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Could not create directory: " + directory.getAbsolutePath());
        }
        if (!directory.isDirectory()) {
            throw new IOException("Expected a directory but found a non-directory path: " + directory.getAbsolutePath());
        }
        return directory;
    }

    static File resolveTrainingDirectory(File groundTruthDirectory) {
        Objects.requireNonNull(groundTruthDirectory, "groundTruthDirectory");
        return groundTruthDirectory;
    }

    static File resolveValidationInputDirectory(File validationDirectory) {
        return requireNonNullDirectory(
                validationDirectory,
                "Validation input directory resolution requires validationDirectory."
        );
    }

    static File trainingArtifactReturnValue(File trainingDirectory) {
        return new File(trainingDirectory, "models");
    }


    static File resolveValidationResultsFolder(File resultsDirectory) {
        File root = requireNonNullDirectory(
                resultsDirectory,
                "ASTRA validation-results routing requires resultsDirectory."
        );
        return ensureSubdirectoryExists(root, "validation");
    }

    static File resolveTrainingResultsFolder(File resultsDirectory) {
        File root = requireNonNullDirectory(
                resultsDirectory,
                "ASTRA training-results routing requires resultsDirectory."
        );
        return ensureSubdirectoryExists(root, "training");
    }

    static File resolveValidationMetricsHelperFile(File extensionDir) {
        Objects.requireNonNull(extensionDir, "extensionDir");
        return new File(extensionDir, VALIDATION_METRICS_HELPER_RELATIVE_PATH);
    }

    private static File resolveInstalledExtensionRoot() throws IOException {
        List<File> extensionDirectories = QuPathGUI.getExtensionCatalogManager()
                .getCatalogManagedInstalledJars()
                .stream()
                .map(Path::getParent)
                .filter(Objects::nonNull)
                .map(Path::toString)
                .map(File::new)
                .distinct()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .filter(dir -> new File(dir, VALIDATION_METRICS_HELPER_RELATIVE_PATH).isFile())
                .collect(Collectors.toList());

        if (extensionDirectories.isEmpty()) {
            throw new IOException("ASTRA validation could not locate an installed extension directory containing " + VALIDATION_METRICS_HELPER_RELATIVE_PATH + ".");
        }
        if (extensionDirectories.size() > 1) {
            throw new IOException("ASTRA validation found multiple installed extension directories containing " + VALIDATION_METRICS_HELPER_RELATIVE_PATH + ": " + extensionDirectories);
        }
        return extensionDirectories.get(0);
    }

    private static void requireSuccessfulProcessExit(VirtualEnvironmentRunner runner, String label) throws IOException, InterruptedException {
        Objects.requireNonNull(runner, "runner");
        Process process = runner.getProcess();
        if (process == null) {
            throw new IOException(label + " did not start a process.");
        }
        int exitValue = process.waitFor();
        if (exitValue != 0) {
            throw new IOException(label + " exited with value " + exitValue + ". Please check the process log for details.");
        }
    }


    private Future<?> submitTileReadTask(ExecutorService executor, TileFile tileFile) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(tileFile, "tileFile");
        return executor.submit(() -> tileFile.setCandidates(readObjectsFromTileFile(tileFile)));
    }

    private static void awaitTileReadTasks(List<Future<?>> tasks, String label) throws IOException, InterruptedException {
        for (Future<?> task : tasks) {
            try {
                task.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IOException(label + " failed: " + cause.getMessage(), cause);
            }
        }
    }

    private static void awaitExecutorTermination(ExecutorService executor, String label) throws IOException, InterruptedException {
        if (executor.awaitTermination(10, TimeUnit.MINUTES)) {
            return;
        }
        executor.shutdownNow();
        if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
            throw new IOException(label + " did not terminate within the allowed timeout.");
        }
    }

    private static int determineTileReadThreadCount() {
        return Math.max(1, Math.min(5, Runtime.getRuntime().availableProcessors()));
    }

    private static void requireExecutionModelReference(AstraCellpose2D cp) {
        resolveExecutionModelReference(cp);
    }

    static String resolveExecutionModelReference(AstraCellpose2D cp) {
        Objects.requireNonNull(cp, "cp");
        String executionModelReference = explicitExecutionModelReference(cp);
        if (executionModelReference == null) {
            throw new IllegalStateException(
                    "ASTRA methods require an explicit execution model reference passed to the builder. " +
                            "That reference may be either a promoted model path or a shipped model name. modelFile is not used."
            );
        }
        return executionModelReference;
    }

    static String resolveExecutionModelDisplayName(AstraCellpose2D cp) {
        return basename(resolveExecutionModelReference(cp));
    }

    static File resolveValidationResultsFile(File validationOutputDirectory) {
        Objects.requireNonNull(validationOutputDirectory, "validationOutputDirectory");
        return new File(validationOutputDirectory, "validation_results.csv");
    }

    static File resolveTrainingResultsFile(File trainingResultsFolder) {
        Objects.requireNonNull(trainingResultsFolder, "trainingResultsFolder");
        return new File(trainingResultsFolder, "training_results.csv");
    }

    static File resolveTrainingGraphFile(File trainingResultsFolder) {
        Objects.requireNonNull(trainingResultsFolder, "trainingResultsFolder");
        return new File(trainingResultsFolder, "training_graph.png");
    }

    @SuppressWarnings("unchecked")
    private static ImageData<BufferedImage> castImageData(ImageData<?> imageData) {
        return (ImageData<BufferedImage>) imageData;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ImageServer<BufferedImage> concatChannelsSafely(ImageServer<BufferedImage> baseServer, ImageServer<BufferedImage> extraServer) {
        return (ImageServer<BufferedImage>) new TransformedServerBuilder(baseServer).concatChannels((ImageServer) extraServer).build();
    }

}
