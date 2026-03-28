package qupath.ext.astra;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.ResultsTable;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.image.WritableImage;
import org.bytedeco.opencv.opencv_core.Mat;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cellpose.Cellpose2D;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.lib.analysis.features.ObjectMeasurements;
import qupath.lib.geom.ImmutableDimension;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.*;
import qupath.lib.objects.CellTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;
import qupath.opencv.ops.ImageDataOp;
import qupath.opencv.ops.ImageDataServer;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.OpenCVTools;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ASTRA-owned Cellpose2D subclass that carries ASTRA training/QC behavior
 * without requiring edits inside the upstream Cellpose2D class.
 */
public class AstraCellpose2D extends Cellpose2D {

    private static final Logger logger = LoggerFactory.getLogger(AstraCellpose2D.class);

    private File astraQcDirectory;
    private File astraResultsDirectory;

    public AstraCellpose2D() {
        super();
    }

    public static AstraCellposeBuilder builder(String modelPath) {
        return new AstraCellposeBuilder(modelPath);
    }

    public static AstraCellposeBuilder builder(File builderPath) {
        return new AstraCellposeBuilder(builderPath);
    }

    static AstraCellpose2D fromBase(Cellpose2D base) {
        AstraCellpose2D astra = new AstraCellpose2D();
        copyCellpose2DState(base, astra);
        return astra;
    }

    void configureAstraState(File modelDirectory, File trainingRootDirectory, File tempDirectory, File qcDirectory, File resultsDirectory) {
        this.modelDirectory = modelDirectory;
        this.groundTruthDirectory = trainingRootDirectory;
        this.astraQcDirectory = qcDirectory;
        this.astraResultsDirectory = resultsDirectory;

        this.tempDirectory = tempDirectory;
    }

    @Override
    public File getTrainingDirectory() {
        return resolveTrainingDirectory(groundTruthDirectory);
    }

    @Override
    public File getValidationDirectory() {
        return resolveValidationDirectory(astraQcDirectory);
    }

    @Override
    public File train() {
        requireExplicitModel(this);
        try {
            if (this.cleanTrainingDir) {
                cleanDirectory(this.groundTruthDirectory);
                saveTrainingImages();
            }

            runTraining();

            ResultsTable parsedTrainingResults = parseTrainingResultsAstra();
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

    public ResultsTable runQC() throws IOException, InterruptedException {
        requireAstraQcDirectory();
        requireExplicitModel(this);
        runCellposeOnValidationImagesAstra();
        ResultsTable results = runCellposeQCAstra();
        setQcResults(results);
        return results;
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
        requireExplicitModel(this);
        Objects.requireNonNull(requests, "requests");
        if (requests.isEmpty()) {
            return List.of();
        }

        File batchTempDirectory = ensureDirectoryExists(this.tempDirectory);
        cleanDirectory(batchTempDirectory);

        List<BatchEntryContext> contexts = buildBatchContexts(requests);
        List<TileFile> allTiles = stageBatchTiles(contexts, batchTempDirectory);

        if (allTiles.isEmpty()) {
            return buildEmptyBatchResults(contexts);
        }

        runCellpose(allTiles);
        return collectBatchResults(contexts, allTiles);
    }

    public List<BatchInferenceResult> runBatchInference(BatchInferenceRequest... requests) throws IOException, InterruptedException {
        Objects.requireNonNull(requests, "requests");
        return runBatchInference(Arrays.asList(requests));
    }

    /**
     * Backward-compatible tuning wrapper over the generalized ASTRA batch
     * inference entrypoint.
     */
    public List<TuningImageResult> tune(List<TuningImageRequest> requests) throws IOException, InterruptedException {
        Objects.requireNonNull(requests, "requests");
        List<BatchInferenceRequest> batchRequests = requests.stream()
                .map(request -> new BatchInferenceRequest(request.key(), request.imageData(), request.parents()))
                .toList();
        List<BatchInferenceResult> batchResults = runBatchInference(batchRequests);
        return batchResults.stream()
                .map(result -> new TuningImageResult(
                        result.key(),
                        result.imageData(),
                        result.parentResults().stream()
                                .map(parentResult -> new TuningParentResult(parentResult.parent(), parentResult.detections()))
                                .toList()
                ))
                .toList();
    }

    public List<TuningImageResult> tune(TuningImageRequest... requests) throws IOException, InterruptedException {
        Objects.requireNonNull(requests, "requests");
        return tune(Arrays.asList(requests));
    }

    @Override
    public void showTrainingGraph(boolean show, boolean save) {
        ResultsTable output = getTrainingResults();
        if (output == null) {
            throw new IllegalStateException("ASTRA training graph display requires parsed training results.");
        }

        File trainingResultsFolder = getTrainingResultsFolderAstra();

        final NumberAxis xAxis = new NumberAxis();
        final NumberAxis yAxis = new NumberAxis();
        xAxis.setLabel("Epochs");
        yAxis.setForceZeroInRange(true);
        yAxis.setAutoRanging(false);
        yAxis.setLowerBound(0);
        yAxis.setUpperBound(3.0);

        final LineChart<Number, Number> lineChart = new LineChart<>(xAxis, yAxis);
        lineChart.setTitle("Cellpose Training");

        XYChart.Series<Number, Number> loss = new XYChart.Series<>();
        XYChart.Series<Number, Number> lossTest = new XYChart.Series<>();
        loss.setName("Loss");
        lossTest.setName("Loss Test");

        for (int i = 0; i < output.getCounter(); i++) {
            loss.getData().add(new XYChart.Data<>(output.getValue("Epoch", i), output.getValue("Loss", i)));
            lossTest.getData().add(new XYChart.Data<>(output.getValue("Epoch", i), output.getValue("Validation Loss", i)));
        }

        lineChart.getData().add(loss);
        lineChart.getData().add(lossTest);

        FXUtils.runOnApplicationThread(() -> {
            Dialog<ButtonType> dialog = Dialogs.builder()
                    .content(lineChart)
                    .title("Cellpose Training")
                    .buttons("Close")
                    .buttons(ButtonType.CLOSE)
                    .build();

            if (show) {
                dialog.show();
            }

            if (save) {
                File trainingGraphFile = resolveTrainingGraphFile(trainingResultsFolder);
                lineChart.setAnimated(false);
                lineChart.setCreateSymbols(false);
                lineChart.setPrefSize(1000, 700);
                new javafx.scene.Scene(new javafx.scene.Group(lineChart));
                lineChart.applyCss();
                lineChart.layout();
                WritableImage writableImage = lineChart.snapshot(null, null);
                RenderedImage renderedImage = SwingFXUtils.fromFXImage(writableImage, null);

                logger.info("Saving Training Graph to {}", trainingGraphFile.getName());

                try {
                    ImageIO.write(renderedImage, "png", trainingGraphFile);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not write ASTRA training graph image.", e);
                }
            }
        });
    }

    @Override
    public void showTrainingGraph() {
        showTrainingGraph(true, true);
    }

    private void runCellposeOnValidationImagesAstra() {
        String qcModelPath = resolveExplicitModelPath(this);
        String qcModelName = resolveExplicitModelName(this);

        logger.info("Running the model {} on the validation images to obtain labels for QC", qcModelName);

        File tmpDirectory = this.tempDirectory;
        String tmpModel = this.model;

        try {
            this.tempDirectory = getValidationDirectory();
            this.model = qcModelPath;
            runCellpose(null);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("ASTRA QC prediction on validation images failed.", e);
        } finally {
            this.tempDirectory = tmpDirectory;
            this.model = tmpModel;
        }
    }

    private ResultsTable runCellposeQCAstra() throws IOException, InterruptedException {
        File qcFolder = getQCFolderAstra();
        ensureDirectoryExists(qcFolder);

        File extensionRoot = resolveInstalledExtensionRoot();
        File qcPythonFile = resolveQcPythonFile(extensionRoot);
        if (!qcPythonFile.isFile()) {
            throw new IOException("ASTRA QC script was not found: " + qcPythonFile.getAbsolutePath());
        }

        VirtualEnvironmentRunner qcRunner = getVirtualEnvironmentRunner();
        String qcModelName = resolveExplicitModelName(this);

        List<String> qcArguments = new ArrayList<>(Arrays.asList(
                qcPythonFile.getAbsolutePath(),
                getValidationDirectory().getAbsolutePath(),
                qcModelName,
                qcFolder.getAbsolutePath()
        ));

        qcRunner.setArguments(qcArguments);
        qcRunner.runCommand(true);

        File qcResultsFile = resolveQcResultsFile(qcFolder);
        if (!qcResultsFile.isFile()) {
            throw new IOException("ASTRA QC results file was not produced: " + qcResultsFile.getAbsolutePath());
        }

        return ResultsTable.open(qcResultsFile.getAbsolutePath());
    }

    private ResultsTable parseTrainingResultsAstra() throws IOException {
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

        File trainingResultsFile = resolveTrainingResultsFile(getTrainingResultsFolderAstra());
        logger.info("Saving Training Results to {}", trainingResultsFile.getAbsolutePath());
        trainingResults.save(trainingResultsFile.getAbsolutePath());
        return trainingResults;
    }

    private void saveTrainingGraphAfterTraining() throws IOException {
        ResultsTable trainingResults = getTrainingResults();
        if (trainingResults == null) {
            throw new IllegalStateException("ASTRA training graph save requires parsed training results.");
        }

        File trainingResultsFolder = getTrainingResultsFolderAstra();
        File trainingGraphFile = resolveTrainingGraphFile(trainingResultsFolder);

        RuntimeException[] runtimeError = new RuntimeException[1];
        IOException[] ioError = new IOException[1];
        CountDownLatch latch = new CountDownLatch(1);

        FXUtils.runOnApplicationThread(() -> {
            try {
                final NumberAxis xAxis = new NumberAxis();
                final NumberAxis yAxis = new NumberAxis();
                xAxis.setLabel("Epochs");
                yAxis.setForceZeroInRange(true);
                yAxis.setAutoRanging(false);
                yAxis.setLowerBound(0);
                yAxis.setUpperBound(3.0);

                final LineChart<Number, Number> lineChart = new LineChart<>(xAxis, yAxis);
                lineChart.setTitle("Cellpose Training");
                lineChart.setAnimated(false);
                lineChart.setCreateSymbols(false);
                lineChart.setPrefSize(1000, 700);

                XYChart.Series<Number, Number> loss = new XYChart.Series<>();
                XYChart.Series<Number, Number> lossTest = new XYChart.Series<>();
                loss.setName("Loss");
                lossTest.setName("Loss Test");

                for (int i = 0; i < trainingResults.getCounter(); i++) {
                    loss.getData().add(new XYChart.Data<>(trainingResults.getValue("Epoch", i), trainingResults.getValue("Loss", i)));
                    lossTest.getData().add(new XYChart.Data<>(trainingResults.getValue("Epoch", i), trainingResults.getValue("Validation Loss", i)));
                }

                lineChart.getData().add(loss);
                lineChart.getData().add(lossTest);
                new javafx.scene.Scene(new javafx.scene.Group(lineChart));
                lineChart.applyCss();
                lineChart.layout();

                WritableImage writableImage = lineChart.snapshot(null, null);
                RenderedImage renderedImage = SwingFXUtils.fromFXImage(writableImage, null);
                if (!ImageIO.write(renderedImage, "png", trainingGraphFile)) {
                    ioError[0] = new IOException("Could not write training graph image: no PNG writer available.");
                }
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
            ImageData<BufferedImage> imageData = context.imageData();
            PixelCalibration resolution = imageData.getServer().getPixelCalibration();

            if (Double.isFinite(pixelSize) && pixelSize > 0) {
                double downsample = pixelSize / resolution.getAveragedPixelSize().doubleValue();
                resolution = resolution.createScaledInstance(downsample, downsample);
            }

            ImageDataServer<BufferedImage> opServer = ImageOps.buildServer(imageData, op, resolution, tileWidth, tileHeight);
            ImageServer<BufferedImage> server = imageData.getServer();
            PixelCalibration calibration = server.getPixelCalibration();

            double downsample = 1.0;
            if (Double.isFinite(pixelSize) && pixelSize > 0) {
                downsample = pixelSize / calibration.getAveragedPixelSize().doubleValue();
            }

            double expansion = cellExpansion / calibration.getAveragedPixelSize().doubleValue();
            context.setServer(server);
            context.setCalibration(calibration);
            context.setFinalDownsample(downsample);
            context.setExpansion(expansion);

            int tileSequence = 0;
            for (PathObject realParent : context.parents()) {
                ROI roi = realParent.getROI();
                int pad = (int) (padding / calibration.getAveragedPixelSize().doubleValue());
                ROI paddedRoi = ROIs.createRectangleROI(
                        roi.getBoundsX() - pad,
                        roi.getBoundsY() - pad,
                        roi.getBoundsWidth() + 2 * pad,
                        roi.getBoundsHeight() + 2 * pad
                );

                PathObject paddedParent = PathObjects.createAnnotationObject(paddedRoi);
                RegionRequest request = RegionRequest.createInstance(
                        opServer.getPath(),
                        opServer.getDownsampleForResolution(0),
                        paddedParent.getROI()
                );

                Collection<? extends ROI> tiledRois = RoiTools.computeTiledROIs(
                        paddedParent.getROI(),
                        ImmutableDimension.getInstance((int) (tileWidth * downsample), (int) (tileWidth * downsample)),
                        ImmutableDimension.getInstance((int) (tileWidth * downsample * 1.5), (int) (tileHeight * downsample * 1.5)),
                        true,
                        (int) (overlap * downsample)
                );

                List<RegionRequest> tiles = tiledRois.stream()
                        .map((ROI roiTile) -> RegionRequest.createInstance(opServer.getPath(), opServer.getDownsampleForResolution(0), roiTile))
                        .collect(Collectors.toList());

                ImageDataOp opWithPreprocessing = buildPreprocessingOp(imageData, paddedParent, request);

                logger.info("ASTRA batch inference staging {} tiles for {} / {}", tiles.size(), context.key(), realParent);
                for (RegionRequest tile : tiles) {
                    allTiles.add(saveBatchTileImage(opWithPreprocessing, imageData, tile, realParent, context.index(), tileSequence++, batchTempDirectory));
                }
            }
        }

        return allTiles;
    }

    private ImageDataOp buildPreprocessingOp(ImageData<BufferedImage> imageData, PathObject paddedParent, RegionRequest request) throws IOException, InterruptedException {
        ArrayList<ImageOp> fullPreprocess = new ArrayList<>();
        fullPreprocess.add(ImageOps.Core.ensureType(PixelType.FLOAT32));

        if (globalPreprocess) {
            if (globalPreprocessingProvidedByUser == null) {
                List<ImageOp> splitMergeListImageOp = new ArrayList<>();
                for (var entry : this.normalizeChannelPercentilesGlobalMap.entrySet()) {
                    splitMergeListImageOp.add(computeNormalizationImageOps(
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
        ImagePlus imp = OpenCVTools.matToImagePlus("ASTRA-Tune", mat);

        String fileName = String.format(
                "Tune_%04d_%06d_x%d_y%d_z%d_t%d.tif",
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
        } else {
            IJ.save(imp, tempFile.getAbsolutePath());
        }

        return new TileFile(request, tempFile, parent);
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
        List<CandidateObject> filteredDetections = filterDetections(rawCandidates);

        Geometry mask = parent.getROI().getGeometry();
        List<PathObject> finalObjects = new ArrayList<>();

        for (CandidateObject candidate : filteredDetections) {
            try {
                PathObject pathObject = convertToObject(
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
                        .map(AstraCellpose2D::objectToCell)
                        .collect(Collectors.toList());
                cells = CellTools.constrainCellOverlaps(cells);
                finalObjects = cells.stream()
                        .map(cell -> cellToObject(cell, creatorFun))
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

    public record BatchInferenceRequest(String key, ImageData<BufferedImage> imageData, Collection<? extends PathObject> parents) {
        public BatchInferenceRequest {
            Objects.requireNonNull(imageData, "imageData");
            Objects.requireNonNull(parents, "parents");
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



    public record TuningImageRequest(String key, ImageData<BufferedImage> imageData, Collection<? extends PathObject> parents) {
        public TuningImageRequest {
            Objects.requireNonNull(imageData, "imageData");
            Objects.requireNonNull(parents, "parents");
        }

        public TuningImageRequest(ImageData<BufferedImage> imageData, Collection<? extends PathObject> parents) {
            this(null, imageData, parents);
        }
    }

    public record TuningParentResult(PathObject parent, List<PathObject> detections) {
        public TuningParentResult {
            Objects.requireNonNull(parent, "parent");
            detections = detections == null ? List.of() : List.copyOf(detections);
        }
    }

    public record TuningImageResult(String key, ImageData<BufferedImage> imageData, List<TuningParentResult> parentResults) {
        public TuningImageResult {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(imageData, "imageData");
            parentResults = parentResults == null ? List.of() : List.copyOf(parentResults);
        }

        public Map<PathObject, List<PathObject>> byParent() {
            Map<PathObject, List<PathObject>> out = new LinkedHashMap<>();
            for (TuningParentResult parentResult : parentResults) {
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


    private void requireAstraQcDirectory() throws IOException {
        if (astraQcDirectory == null) {
            throw new IOException("ASTRA QC requires qcDirectory to be set by the ASTRA builder.");
        }
        if (!astraQcDirectory.exists() || !astraQcDirectory.isDirectory()) {
            throw new IOException("ASTRA QC requires qcDirectory to exist as a directory: " + astraQcDirectory.getAbsolutePath());
        }
    }



    private File getQCFolderAstra() {
        return resolveQcFolder(astraResultsDirectory);
    }

    private File getTrainingResultsFolderAstra() {
        return resolveTrainingResultsFolder(astraResultsDirectory);
    }





    private void setTrainingResults(ResultsTable resultsTable) {
        setTrainingResultsTable(resultsTable);
    }

    private void setQcResults(ResultsTable resultsTable) {
        setQCResultsTable(resultsTable);
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
        return child;
    }

    private static boolean isUpstreamDefaultTrainingDirectory(File directory) {
        return directory != null && "cellpose-training".equals(directory.getName());
    }

    private static String explicitModel(AstraCellpose2D cp) {
        String explicit = cp.model;
        return explicit != null && !explicit.isBlank() ? explicit.trim() : null;
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

    static File resolveQcDirectory(File rootDirectory, File qcDirectory) {
        Objects.requireNonNull(rootDirectory, "rootDirectory");
        return qcDirectory != null ? qcDirectory : new File(rootDirectory, "qc");
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
        return directory;
    }

    static File resolveTrainingDirectory(File groundTruthDirectory) {
        Objects.requireNonNull(groundTruthDirectory, "groundTruthDirectory");
        return groundTruthDirectory;
    }

    static File resolveValidationDirectory(File qcDirectory) {
        return requireNonNullDirectory(
                qcDirectory,
                "ASTRA validation/QC directory resolution requires qcDirectory."
        );
    }

    static File trainingArtifactReturnValue(File trainingDirectory) {
        return new File(trainingDirectory, "models");
    }

    static File resolveQcFolder(File resultsDirectory) {
        File root = requireNonNullDirectory(
                resultsDirectory,
                "ASTRA QC results routing requires resultsDirectory."
        );
        return ensureSubdirectoryExists(root, "qc");
    }

    static File resolveTrainingResultsFolder(File resultsDirectory) {
        File root = requireNonNullDirectory(
                resultsDirectory,
                "ASTRA training-results routing requires resultsDirectory."
        );
        return ensureSubdirectoryExists(root, "training");
    }

    static File resolveQcPythonFile(File extensionDir) {
        Objects.requireNonNull(extensionDir, "extensionDir");
        return new File(extensionDir, "QC/run-cellpose-qc.py");
    }

    private static File resolveInstalledExtensionRoot() throws IOException {
        List<File> extensionDirList = QuPathGUI.getExtensionCatalogManager()
                .getCatalogManagedInstalledJars()
                .parallelStream()
                .map(Path::getParent)
                .filter(Objects::nonNull)
                .map(Path::toString)
                .map(File::new)
                .distinct()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .filter(dir -> new File(dir, "QC/run-cellpose-qc.py").isFile())
                .collect(Collectors.toList());

        if (extensionDirList.isEmpty()) {
            throw new IOException("ASTRA QC could not locate an installed extension directory containing QC/run-cellpose-qc.py.");
        }
        if (extensionDirList.size() > 1) {
            throw new IOException("ASTRA QC found multiple installed extension directories containing QC/run-cellpose-qc.py: " + extensionDirList);
        }
        return extensionDirList.get(0);
    }

    private static void requireExplicitModel(AstraCellpose2D cp) {
        resolveExplicitModelPath(cp);
    }

    static String resolveExplicitModelPath(AstraCellpose2D cp) {
        Objects.requireNonNull(cp, "cp");
        String explicit = explicitModel(cp);
        if (explicit == null) {
            throw new IllegalStateException(
                    "ASTRA methods require an explicit model path or model name passed to the builder. modelFile is not used."
            );
        }
        return explicit;
    }

    static String resolveExplicitModelName(AstraCellpose2D cp) {
        return basename(resolveExplicitModelPath(cp));
    }

    static File resolveQcResultsFile(File qcOutputDirectory) {
        Objects.requireNonNull(qcOutputDirectory, "qcOutputDirectory");
        return new File(qcOutputDirectory, "qc_results.csv");
    }

    static File resolveTrainingResultsFile(File trainingResultsFolder) {
        Objects.requireNonNull(trainingResultsFolder, "trainingResultsFolder");
        return new File(trainingResultsFolder, "training_results.csv");
    }

    static File resolveTrainingGraphFile(File trainingResultsFolder) {
        Objects.requireNonNull(trainingResultsFolder, "trainingResultsFolder");
        return new File(trainingResultsFolder, "training_graph.png");
    }
}
