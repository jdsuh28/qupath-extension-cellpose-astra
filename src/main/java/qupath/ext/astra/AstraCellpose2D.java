package qupath.ext.astra;

import ij.measure.ResultsTable;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.image.WritableImage;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.cellpose.Cellpose2D;
import qupath.ext.biop.cellpose.CellposeExtension;
import qupath.ext.biop.cmd.VirtualEnvironmentRunner;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.FXUtils;
import qupath.lib.gui.QuPathGUI;

import javax.imageio.ImageIO;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.net.URL;
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
        astra.astraQcDirectory = readOptionalFileField(base, "qcDirectory");
        astra.astraResultsDirectory = readOptionalFileField(base, "resultsDirectory");
        return astra;
    }

    void configureAstraState(File modelDirectory, File trainingRootDirectory, File tempDirectory, File qcDirectory, File resultsDirectory) {
        this.modelDirectory = modelDirectory;
        this.groundTruthDirectory = trainingRootDirectory;
        this.astraQcDirectory = qcDirectory;
        this.astraResultsDirectory = resultsDirectory;

        writeRequiredField(this, "tempDirectory", tempDirectory);
        writeOptionalField(this, "qcDirectory", qcDirectory);
        writeOptionalField(this, "resultsDirectory", resultsDirectory);
    }

    @Override
    public File getTrainingDirectory() {
        return AstraHooks.resolveTrainingDirectory(groundTruthDirectory);
    }

    @Override
    public File getValidationDirectory() {
        return AstraHooks.resolveValidationDirectory(groundTruthDirectory, astraQcDirectory);
    }

    @Override
    public File train() {
        try {
            if (this.cleanTrainingDir) {
                invokePrivateVoid(this, "cleanDirectory", new Class<?>[]{File.class}, this.groundTruthDirectory);
                saveTrainingImages();
            }

            invokePrivateVoid(this, "runTraining", new Class<?>[0]);

            ResultsTable parsedTrainingResults = parseTrainingResultsAstra();
            setTrainingResults(parsedTrainingResults);
            AstraHooks.onTrainingResultsParsed(this, parsedTrainingResults);

            if (AstraHooks.saveTrainingGraphAfterTraining(this)) {
                saveTrainingGraphAfterTraining();
            }

            boolean skipModelMove = AstraHooks.skipModelMove(this);
            boolean skipAutomaticQc = AstraHooks.skipAutomaticQc(this);

            if (!skipModelMove) {
                File movedModel = (File) invokePrivate(this, "moveRenameAndReturnModelFile", new Class<?>[0]);
                setModelFile(movedModel);
            }

            if (!skipAutomaticQc) {
                if (skipModelMove) {
                    throw new IllegalStateException("ASTRA automatic QC cannot run when model promotion is disabled.");
                }
                runCellposeOnValidationImagesAstra();
                setQcResults(runCellposeQCAstra());
            }

            return skipModelMove ? AstraHooks.trainingArtifactReturnValue(this, getTrainingDirectory()) : getModelFile();
        } catch (IOException | InterruptedException e) {
            logger.error("Error while running ASTRA cellpose training: {}", e.getMessage(), e);
            return null;
        }
    }

    public ResultsTable runQC() throws IOException, InterruptedException {
        requireAstraQcDirectory();
        runCellposeOnValidationImagesAstra();
        ResultsTable results = runCellposeQCAstra();
        setQcResults(results);
        return results;
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
                String trainingModelName = safeModelDisplayName();
                File trainingGraphFile = AstraHooks.resolveTrainingGraphFile(trainingResultsFolder, trainingModelName);
                WritableImage writableImage = new WritableImage((int) dialog.getWidth(), (int) dialog.getHeight());
                dialog.getDialogPane().snapshot(null, writableImage);
                RenderedImage renderedImage = SwingFXUtils.fromFXImage(writableImage, null);

                logger.info("Saving Training Graph to {}", trainingGraphFile.getName());

                try {
                    ImageIO.write(renderedImage, "png", trainingGraphFile);
                } catch (IOException e) {
                    logger.error("Could not write training graph image {} in {}.", trainingGraphFile.getName(), trainingGraphFile.getParent());
                    logger.error("Error Message", e);
                }
            }
        });
    }

    @Override
    public void showTrainingGraph() {
        showTrainingGraph(true, true);
    }

    private void runCellposeOnValidationImagesAstra() {
        String qcModelPath = AstraHooks.resolveQcModelPathForValidation(this);
        String qcModelName = AstraHooks.resolveQcModelNameForQc(this);

        logger.info("Running the model {} on the validation images to obtain labels for QC", qcModelName);

        File tmpDirectory = readRequiredFileField(this, "tempDirectory");
        String tmpModel = readRequiredStringField(this, "model");

        try {
            writeRequiredField(this, "tempDirectory", getValidationDirectory());
            writeRequiredField(this, "model", qcModelPath);
            invokePrivateVoid(this, "runCellpose", new Class<?>[]{List.class}, (Object) null);
        } catch (IOException | InterruptedException e) {
            if (AstraHooks.enabled()) {
                throw new IllegalStateException("ASTRA QC prediction on validation images failed.", e);
            }
            logger.error(e.getMessage(), e);
        } finally {
            writeRequiredField(this, "tempDirectory", tmpDirectory);
            writeRequiredField(this, "model", tmpModel);
        }
    }

    private ResultsTable runCellposeQCAstra() throws IOException, InterruptedException {
        File qcFolder = getQCFolderAstra();
        if (!qcFolder.exists() && !qcFolder.mkdirs()) {
            throw new IOException("Could not create QC directory: " + qcFolder.getAbsolutePath());
        }

        String cellposeVersion = getExtensionVersionReflectively();
        List<File> extensionDirList = QuPathGUI.getExtensionCatalogManager()
                .getCatalogManagedInstalledJars()
                .parallelStream()
                .filter(e -> AstraHooks.matchesInstalledExtensionJar(e.toString(), cellposeVersion))
                .map(Path::getParent)
                .map(Path::toString)
                .map(File::new)
                .collect(Collectors.toList());

        if (extensionDirList.isEmpty()) {
            if (AstraHooks.enabled()) {
                throw new IOException("ASTRA QC could not locate the installed extension directory.");
            }
            logger.warn("Cellpose extension not installed ; cannot find QC script");
            return null;
        }

        File qcPythonFile = AstraHooks.resolveQcPythonFile(extensionDirList.get(0));
        if (!qcPythonFile.exists()) {
            if (AstraHooks.enabled()) {
                throw new IOException("ASTRA QC script was not found: " + qcPythonFile.getAbsolutePath());
            }
            logger.warn("File {} was not found in {}.\nPlease download it from {}",
                    qcPythonFile.getName(),
                    extensionDirList.get(0).getAbsolutePath(),
                    new AstraCellposeExtension().getRepository().getUrlString());
            return null;
        }

        VirtualEnvironmentRunner qcRunner = (VirtualEnvironmentRunner) invokePrivate(this, "getVirtualEnvironmentRunner", new Class<?>[0]);

        String qcModelName = AstraHooks.resolveQcModelNameForQc(this);
        List<String> qcArguments = new ArrayList<>(Arrays.asList(
                qcPythonFile.getAbsolutePath(),
                getValidationDirectory().getAbsolutePath(),
                qcModelName
        ));
        if (AstraHooks.enabled()) {
            qcArguments.add(qcFolder.getAbsolutePath());
        }

        qcRunner.setArguments(qcArguments);
        qcRunner.runCommand(true);

        File qcResultsFile = AstraHooks.resolveQcResultsFile(getValidationDirectory(), qcModelName, qcFolder);
        if (!qcResultsFile.exists()) {
            if (AstraHooks.enabled()) {
                throw new IOException("ASTRA QC results file was not produced: " + qcResultsFile.getAbsolutePath());
            }
            logger.warn("No QC results file named {} found in {}\nCheck the logger for a potential reason",
                    qcResultsFile.getName(),
                    qcResultsFile.getParent());
            logger.warn("In case you are missing the 'skimage' module, simply run 'pip install scikit-image' in your cellpose environment");
            return null;
        }

        File finalQCResultFile = AstraHooks.resolveFinalQcResultFile(qcFolder, qcResultsFile);
        if (!qcResultsFile.getAbsoluteFile().equals(finalQCResultFile.getAbsoluteFile())) {
            FileUtils.moveFile(qcResultsFile, finalQCResultFile);
        }

        return ResultsTable.open(finalQCResultFile.getAbsolutePath());
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

        if (AstraHooks.allowLegacyTrainingResultsSave(this)) {
            String trainingModelName = safeModelDisplayName();
            File trainingResultsFile = AstraHooks.resolveTrainingResultsFile(getTrainingResultsFolderAstra(), trainingModelName);
            logger.info("Saving Training Results to {}", trainingResultsFile.getParent());
            trainingResults.save(trainingResultsFile.getAbsolutePath());
        }

        return trainingResults;
    }

    private void saveTrainingGraphAfterTraining() throws IOException {
        ResultsTable trainingResults = getTrainingResults();
        if (trainingResults == null) {
            throw new IllegalStateException("ASTRA training graph save requires parsed training results.");
        }

        String trainingModelName = safeModelDisplayName();
        File trainingResultsFolder = getTrainingResultsFolderAstra();
        File trainingGraphFile = AstraHooks.resolveTrainingGraphFile(trainingResultsFolder, trainingModelName);

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


    private void requireAstraQcDirectory() throws IOException {
        if (astraQcDirectory == null) {
            throw new IOException("ASTRA QC requires qcDirectory to be set by the ASTRA builder.");
        }
        if (!astraQcDirectory.exists() || !astraQcDirectory.isDirectory()) {
            throw new IOException("ASTRA QC requires qcDirectory to exist as a directory: " + astraQcDirectory.getAbsolutePath());
        }
    }

    private String safeModelDisplayName() {
        try {
            return AstraHooks.resolveModelDisplayName(this);
        } catch (IllegalStateException e) {
            return "training";
        }
    }

    private File getQCFolderAstra() {
        return AstraHooks.resolveQcFolder(this.modelDirectory, astraResultsDirectory);
    }

    private File getTrainingResultsFolderAstra() {
        return AstraHooks.resolveTrainingResultsFolder(this.modelDirectory, astraResultsDirectory);
    }

    private File getModelFile() {
        return readOptionalFileField(this, "modelFile");
    }

    private void setModelFile(File file) {
        writeRequiredField(this, "modelFile", file);
    }

    private void setTrainingResults(ResultsTable resultsTable) {
        writeRequiredField(this, "trainingResults", resultsTable);
    }

    private void setQcResults(ResultsTable resultsTable) {
        writeRequiredField(this, "qcResults", resultsTable);
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

    private static String getExtensionVersionReflectively() {
        try {
            Method method = CellposeExtension.class.getDeclaredMethod("getExtensionVersion");
            method.setAccessible(true);
            return (String) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to determine installed Cellpose extension version.", e);
        }
    }

    private static Object invokePrivate(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws IOException, InterruptedException {
        try {
            Method method = Cellpose2D.class.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            throw new IllegalStateException("Unable to invoke Cellpose2D." + methodName + " reflectively.", e);
        }
    }

    private static void invokePrivateVoid(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws IOException, InterruptedException {
        invokePrivate(target, methodName, parameterTypes, args);
    }

    private static File readRequiredFileField(Object target, String name) {
        Object value = readRequiredField(target, name);
        if (value == null) {
            throw new IllegalStateException("Required field '" + name + "' is null.");
        }
        if (!(value instanceof File file)) {
            throw new IllegalStateException("Field '" + name + "' is not a File.");
        }
        return file;
    }

    private static String readRequiredStringField(Object target, String name) {
        Object value = readRequiredField(target, name);
        if (!(value instanceof String stringValue)) {
            throw new IllegalStateException("Field '" + name + "' is not a String.");
        }
        return stringValue;
    }

    private static File readOptionalFileField(Object target, String name) {
        Object value = readOptionalField(target, name);
        return value instanceof File file ? file : null;
    }

    private static Object readRequiredField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read field '" + name + "'.", e);
        }
    }

    private static Object readOptionalField(Object target, String name) {
        try {
            Field field = findOptionalField(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read field '" + name + "'.", e);
        }
    }

    private static void writeRequiredField(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            if (Modifier.isFinal(field.getModifiers())) {
                throw new IllegalStateException("Field '" + name + "' is final.");
            }
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to write field '" + name + "'.", e);
        }
    }

    private static void writeOptionalField(Object target, String name, Object value) {
        try {
            Field field = findOptionalField(target.getClass(), name);
            if (field == null) {
                return;
            }
            if (Modifier.isFinal(field.getModifiers())) {
                throw new IllegalStateException("Field '" + name + "' is final.");
            }
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to write field '" + name + "'.", e);
        }
    }

    private static Field findField(Class<?> type, String name) {
        Field field = findOptionalField(type, name);
        if (field == null) {
            throw new IllegalStateException("Field '" + name + "' was not found in " + type.getName() + " or its superclasses.");
        }
        return field;
    }

    private static Field findOptionalField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
