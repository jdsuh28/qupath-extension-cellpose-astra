package qupath.ext.astra;

import ij.measure.ResultsTable;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import qupath.fx.utils.FXUtils;

import javax.imageio.ImageIO;
import java.awt.image.RenderedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ASTRA-owned publication-quality QC figure renderer.
 *
 * <p>This class intentionally lives in the extension rather than the ASTRA Groovy pipeline repository. The base
 * pipelines own biological and execution logic; the extension owns Cellpose-adjacent interface artifacts, including
 * QC figure rendering. All methods write deterministic PNG files and fail loudly if inputs cannot be rendered.</p>
 */
public final class AstraQcFigures {

    /**
     * Prevents construction of the static QC renderer utility.
     */
    private AstraQcFigures() {
    }

    /**
     * Saves a training-loss QC figure from parsed Cellpose training results.
     *
     * @param trainingResults parsed training results table containing epoch and loss columns.
     * @param outputFile destination PNG file.
     * @throws IOException if the figure cannot be rendered or written.
     * @throws IllegalStateException if {@code trainingResults} is missing.
     */
    public static void saveTrainingLossFigure(ResultsTable trainingResults, File outputFile) throws IOException {
        if (trainingResults == null) {
            throw new IllegalStateException("ASTRA training graph save requires parsed training results.");
        }
        saveOnFxThread("ASTRA training graph", () -> saveCanvasPng(createTrainingLossCanvas(trainingResults), outputFile));
    }

    /**
     * Saves a validation QC figure from parsed Cellpose validation metrics.
     *
     * @param validationResults validation metrics table produced by the ASTRA extension QC helper.
     * @param outputFile destination PNG file.
     * @throws IOException if the figure cannot be rendered or written.
     * @throws IllegalStateException if {@code validationResults} is missing.
     */
    public static void saveValidationQcFigure(ResultsTable validationResults, File outputFile) throws IOException {
        if (validationResults == null) {
            throw new IllegalStateException("ASTRA validation QC figure requires validation results.");
        }
        saveOnFxThread("ASTRA validation QC figure", () -> saveCanvasPng(createValidationQcCanvas(validationResults), outputFile));
    }

    /**
     * Saves a tuning QC figure from the canonical ASTRA tuning results CSV.
     *
     * @param tuningResultsCsv canonical {@code tuning_results.csv} file written by the base ASTRA tuning pipeline.
     * @param outputFile destination PNG file.
     * @throws IOException if the CSV cannot be read or the figure cannot be rendered or written.
     * @throws IllegalStateException if the CSV has no usable tuning result rows.
     */
    public static void saveTuningQcFigure(File tuningResultsCsv, File outputFile) throws IOException {
        List<Map<String, String>> rows = readCsvRows(tuningResultsCsv);
        if (rows.isEmpty()) {
            throw new IllegalStateException("ASTRA tuning QC figure requires at least one tuning result row.");
        }
        saveOnFxThread("ASTRA tuning QC figure", () -> saveCanvasPng(createTuningQcCanvas(rows), outputFile));
    }

    private static Canvas createTrainingLossCanvas(ResultsTable trainingResults) {
        final double width = 1800.0;
        final double height = 1050.0;
        Canvas canvas = new Canvas(width, height);
        GraphicsContext g = canvas.getGraphicsContext2D();

        List<double[]> trainingSeries = extractLossSeries(trainingResults, "Loss");
        List<double[]> validationSeries = extractLossSeries(trainingResults, "Validation Loss");
        double[] epochBounds = resolvePointBounds(trainingSeries, validationSeries, 0, 0.0, 1.0, false);
        double[] lossBounds = resolvePointBounds(trainingSeries, validationSeries, 1, 0.0, 1.0, true);

        fillBackground(g, width, height);
        drawFigureTitle(g, "ASTRA Training QC", "Cellpose-SAM loss trajectory across training epochs", 150.0, 92.0);
        drawAxes(g, 150.0, 190.0, 1180.0, 650.0, epochBounds, lossBounds, "Epoch", "Loss", false);
        drawLineSeries(g, trainingSeries, epochBounds, lossBounds, 150.0, 190.0, 1180.0, 650.0,
                Color.rgb(38, 99, 168), 5.0);
        drawLineSeries(g, validationSeries, epochBounds, lossBounds, 150.0, 190.0, 1180.0, 650.0,
                Color.rgb(206, 91, 44), 5.0);
        drawLegendEntry(g, "Training loss", Color.rgb(38, 99, 168), 150.0, 875.0);
        if (!validationSeries.isEmpty()) {
            drawLegendEntry(g, "Validation loss", Color.rgb(206, 91, 44), 410.0, 875.0);
        }
        drawTrainingSummary(g, trainingSeries, validationSeries, 1390.0, 210.0, 300.0, 360.0);
        return canvas;
    }

    private static Canvas createValidationQcCanvas(ResultsTable results) {
        final double width = 1600.0;
        final double height = 900.0;
        Canvas canvas = new Canvas(width, height);
        GraphicsContext g = canvas.getGraphicsContext2D();

        fillBackground(g, width, height);
        drawFigureTitle(g, "ASTRA Validation QC", "Cellpose-SAM prediction quality against validation masks", 110.0, 92.0);

        LinkedHashMap<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("IoU", meanColumn(results, "Prediction v. GT Intersection over Union"));
        metrics.put("Precision", meanColumn(results, "precision"));
        metrics.put("Recall", meanColumn(results, "recall"));
        metrics.put("F1", meanColumn(results, "f1 score"));
        metrics.put("Panoptic Quality", meanColumn(results, "panoptic_quality"));

        drawMetricBars(g, metrics, 110.0, 190.0, 920.0, 540.0);
        drawCountPanel(g, results, 1080.0, 190.0, 380.0, 540.0);
        drawValidationFooter(g, results, 110.0, 810.0);
        return canvas;
    }

    private static Canvas createTuningQcCanvas(List<Map<String, String>> rows) {
        final double width = 1800.0;
        final double height = 1050.0;
        Canvas canvas = new Canvas(width, height);
        GraphicsContext g = canvas.getGraphicsContext2D();

        List<double[]> scoreSeries = extractCsvSeries(rows, "evaluation_index", "score");
        double[] xBounds = resolvePointBounds(scoreSeries, List.of(), 0, 1.0, Math.max(1.0, scoreSeries.size()), false);
        double[] yBounds = resolvePointBounds(scoreSeries, List.of(), 1, 0.0, 1.0, true);
        Map<String, String> best = bestScoreRow(rows);

        fillBackground(g, width, height);
        drawFigureTitle(g, "ASTRA Tuning QC", "Deterministic Cellpose-SAM parameter search performance", 150.0, 92.0);
        drawAxes(g, 150.0, 190.0, 1180.0, 650.0, xBounds, yBounds, "Evaluation", "Score", false);
        drawLineSeries(g, scoreSeries, xBounds, yBounds, 150.0, 190.0, 1180.0, 650.0,
                Color.rgb(39, 128, 112), 4.5);
        drawBestPoint(g, best, xBounds, yBounds, 150.0, 190.0, 1180.0, 650.0);
        drawLegendEntry(g, "Evaluation score", Color.rgb(39, 128, 112), 150.0, 875.0);
        drawTuningSummary(g, rows, best, 1390.0, 210.0, 310.0, 420.0);
        return canvas;
    }

    private static void fillBackground(GraphicsContext g, double width, double height) {
        g.setFill(Color.WHITE);
        g.fillRect(0, 0, width, height);
    }

    private static void drawFigureTitle(GraphicsContext g, String title, String subtitle, double x, double y) {
        g.setFill(Color.rgb(24, 34, 45));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 44.0));
        g.fillText(title, x, y);
        g.setFill(Color.rgb(86, 96, 108));
        g.setFont(Font.font("Arial", FontWeight.NORMAL, 23.0));
        g.fillText(subtitle, x + 2.0, y + 38.0);
    }

    private static void drawAxes(GraphicsContext g,
                                 double x,
                                 double y,
                                 double width,
                                 double height,
                                 double[] xBounds,
                                 double[] yBounds,
                                 String xLabel,
                                 String yLabel,
                                 boolean yUnitScale) {
        g.setStroke(Color.rgb(222, 228, 235));
        g.setLineWidth(1.5);
        g.setFill(Color.rgb(82, 92, 104));
        g.setFont(Font.font("Arial", FontWeight.NORMAL, 18.0));
        g.setTextAlign(TextAlignment.RIGHT);

        for (int i = 0; i <= 5; i++) {
            double fraction = i / 5.0;
            double yy = y + height - height * fraction;
            double value = yBounds[0] + (yBounds[1] - yBounds[0]) * fraction;
            g.strokeLine(x, yy, x + width, yy);
            g.fillText(yUnitScale ? String.format("%.2f", value) : String.format("%.3g", value), x - 18.0, yy + 6.0);
        }

        g.setTextAlign(TextAlignment.CENTER);
        for (int i = 0; i <= 6; i++) {
            double fraction = i / 6.0;
            double xx = x + width * fraction;
            double value = xBounds[0] + (xBounds[1] - xBounds[0]) * fraction;
            g.strokeLine(xx, y + height, xx, y + height + 8.0);
            g.fillText(String.format("%.0f", value), xx, y + height + 38.0);
        }

        g.setStroke(Color.rgb(34, 45, 58));
        g.setLineWidth(2.2);
        g.strokeLine(x, y, x, y + height);
        g.strokeLine(x, y + height, x + width, y + height);

        g.setFill(Color.rgb(45, 56, 69));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 20.0));
        g.fillText(xLabel, x + width / 2.0, y + height + 84.0);

        g.save();
        g.translate(x - 100.0, y + height / 2.0);
        g.rotate(-90.0);
        g.fillText(yLabel, 0.0, 0.0);
        g.restore();
        g.setTextAlign(TextAlignment.LEFT);
    }

    private static void drawLineSeries(GraphicsContext g,
                                       List<double[]> series,
                                       double[] xBounds,
                                       double[] yBounds,
                                       double x,
                                       double y,
                                       double width,
                                       double height,
                                       Color color,
                                       double lineWidth) {
        if (series.isEmpty()) {
            return;
        }
        g.setStroke(color);
        g.setLineWidth(lineWidth);
        double previousX = Double.NaN;
        double previousY = Double.NaN;
        for (double[] point : series) {
            double px = x + width * normalize(point[0], xBounds[0], xBounds[1]);
            double py = y + height - height * normalize(point[1], yBounds[0], yBounds[1]);
            if (Double.isFinite(previousX) && Double.isFinite(previousY)) {
                g.strokeLine(previousX, previousY, px, py);
            }
            previousX = px;
            previousY = py;
        }
    }

    private static void drawBestPoint(GraphicsContext g,
                                      Map<String, String> best,
                                      double[] xBounds,
                                      double[] yBounds,
                                      double x,
                                      double y,
                                      double width,
                                      double height) {
        if (best == null || best.isEmpty()) {
            return;
        }
        double evaluation = parseDouble(best.get("evaluation_index"));
        double score = parseDouble(best.get("score"));
        if (!Double.isFinite(evaluation) || !Double.isFinite(score)) {
            return;
        }
        double px = x + width * normalize(evaluation, xBounds[0], xBounds[1]);
        double py = y + height - height * normalize(score, yBounds[0], yBounds[1]);
        g.setFill(Color.rgb(24, 34, 45));
        g.fillOval(px - 9.0, py - 9.0, 18.0, 18.0);
        g.setStroke(Color.WHITE);
        g.setLineWidth(3.0);
        g.strokeOval(px - 9.0, py - 9.0, 18.0, 18.0);
    }

    private static void drawLegendEntry(GraphicsContext g, String label, Color color, double x, double y) {
        g.setStroke(color);
        g.setLineWidth(5.0);
        g.strokeLine(x, y, x + 60.0, y);
        g.setFill(Color.rgb(38, 49, 62));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 20.0));
        g.fillText(label, x + 78.0, y + 7.0);
    }

    private static void drawTrainingSummary(GraphicsContext g,
                                            List<double[]> trainingSeries,
                                            List<double[]> validationSeries,
                                            double x,
                                            double y,
                                            double width,
                                            double height) {
        drawPanel(g, x, y, width, height);
        g.setFill(Color.rgb(24, 34, 45));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 25.0));
        g.fillText("Run Summary", x + 28.0, y + 52.0);

        double rowY = y + 105.0;
        rowY = drawSummaryRow(g, "Epochs", String.valueOf(trainingSeries.size()), x + 28.0, rowY);
        rowY = drawSummaryRow(g, "Final training loss", formatLastValue(trainingSeries), x + 28.0, rowY);
        rowY = drawSummaryRow(g, "Best training loss", formatMinimumValue(trainingSeries), x + 28.0, rowY);
        if (!validationSeries.isEmpty()) {
            rowY = drawSummaryRow(g, "Final validation loss", formatLastValue(validationSeries), x + 28.0, rowY);
            drawSummaryRow(g, "Best validation loss", formatMinimumValue(validationSeries), x + 28.0, rowY);
        }
    }

    private static void drawTuningSummary(GraphicsContext g,
                                          List<Map<String, String>> rows,
                                          Map<String, String> best,
                                          double x,
                                          double y,
                                          double width,
                                          double height) {
        drawPanel(g, x, y, width, height);
        g.setFill(Color.rgb(24, 34, 45));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 25.0));
        g.fillText("Best Evaluation", x + 28.0, y + 52.0);

        double rowY = y + 105.0;
        rowY = drawSummaryRow(g, "Evaluations", String.valueOf(rows.size()), x + 28.0, rowY);
        rowY = drawSummaryRow(g, "Best score", formatNumber(best == null ? "" : best.get("score")), x + 28.0, rowY);
        rowY = drawSummaryRow(g, "Best evaluation", valueOrNa(best == null ? "" : best.get("evaluation_index")), x + 28.0, rowY);
        rowY = drawSummaryRow(g, "Target", valueOrNa(best == null ? "" : best.get("target")), x + 28.0, rowY);
        drawSummaryRow(g, "Phase", valueOrNa(best == null ? "" : best.get("phase")), x + 28.0, rowY);
    }

    private static void drawPanel(GraphicsContext g, double x, double y, double width, double height) {
        g.setFill(Color.rgb(247, 249, 252));
        g.fillRoundRect(x, y, width, height, 18.0, 18.0);
        g.setStroke(Color.rgb(219, 225, 233));
        g.setLineWidth(1.5);
        g.strokeRoundRect(x, y, width, height, 18.0, 18.0);
    }

    private static double drawSummaryRow(GraphicsContext g, String label, String value, double x, double y) {
        g.setFill(Color.rgb(91, 101, 113));
        g.setFont(Font.font("Arial", FontWeight.NORMAL, 17.0));
        g.fillText(label, x, y);
        g.setFill(Color.rgb(24, 34, 45));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 25.0));
        g.fillText(value, x, y + 34.0);
        return y + 74.0;
    }

    private static void drawMetricBars(GraphicsContext g,
                                       LinkedHashMap<String, Double> metrics,
                                       double x,
                                       double y,
                                       double width,
                                       double height) {
        g.setFill(Color.rgb(24, 34, 45));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 26.0));
        g.fillText("Segmentation Metrics", x, y);
        g.setFill(Color.rgb(91, 101, 113));
        g.setFont(Font.font("Arial", FontWeight.NORMAL, 18.0));
        g.fillText("Mean values across evaluated validation images", x, y + 32.0);

        double plotX = x + 78.0;
        double plotY = y + 105.0;
        double plotW = width - 130.0;
        double plotH = height - 185.0;

        g.setStroke(Color.rgb(222, 228, 235));
        g.setLineWidth(1.5);
        for (int i = 0; i <= 4; i++) {
            double yy = plotY + plotH - (plotH * i / 4.0);
            g.strokeLine(plotX, yy, plotX + plotW, yy);
            g.setFill(Color.rgb(101, 111, 123));
            g.setFont(Font.font("Arial", FontWeight.NORMAL, 16.0));
            g.fillText(String.format("%.2f", i / 4.0), plotX - 48.0, yy + 5.0);
        }

        int count = metrics.size();
        double gap = 28.0;
        double barW = (plotW - gap * (count - 1)) / count;
        int index = 0;
        Color[] palette = {Color.rgb(38, 99, 168), Color.rgb(39, 128, 112), Color.rgb(206, 91, 44),
                Color.rgb(110, 88, 166), Color.rgb(198, 139, 43)};

        for (Map.Entry<String, Double> entry : metrics.entrySet()) {
            double value = sanitizeUnitMetric(entry.getValue());
            double barH = plotH * value;
            double bx = plotX + index * (barW + gap);
            double by = plotY + plotH - barH;
            g.setFill(palette[index % palette.length]);
            g.fillRoundRect(bx, by, barW, barH, 10.0, 10.0);

            g.setFill(Color.rgb(24, 34, 45));
            g.setFont(Font.font("Arial", FontWeight.BOLD, 18.0));
            g.setTextAlign(TextAlignment.CENTER);
            g.fillText(Double.isFinite(entry.getValue()) ? String.format("%.3f", entry.getValue()) : "NA",
                    bx + barW / 2.0, by - 14.0);
            g.setFill(Color.rgb(67, 77, 89));
            g.setFont(Font.font("Arial", FontWeight.BOLD, 15.0));
            g.fillText(entry.getKey(), bx + barW / 2.0, plotY + plotH + 34.0);
            g.setTextAlign(TextAlignment.LEFT);
            index++;
        }
    }

    private static void drawCountPanel(GraphicsContext g, ResultsTable results, double x, double y, double width, double height) {
        drawPanel(g, x, y, width, height);
        g.setFill(Color.rgb(24, 34, 45));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 26.0));
        g.fillText("Object Counts", x + 34.0, y + 56.0);

        long truePositive = Math.round(sumColumn(results, "true positive"));
        long falsePositive = Math.round(sumColumn(results, "false positive"));
        long falseNegative = Math.round(sumColumn(results, "false negative"));
        long groundTruth = Math.round(sumColumn(results, "n_true"));
        long predicted = Math.round(sumColumn(results, "n_pred"));

        double rowY = y + 115.0;
        rowY = drawCountRow(g, "True positive", truePositive, Color.rgb(39, 128, 112), x + 34.0, rowY);
        rowY = drawCountRow(g, "False positive", falsePositive, Color.rgb(206, 91, 44), x + 34.0, rowY);
        rowY = drawCountRow(g, "False negative", falseNegative, Color.rgb(198, 139, 43), x + 34.0, rowY);
        rowY += 20.0;
        rowY = drawCountRow(g, "Ground truth", groundTruth, Color.rgb(38, 99, 168), x + 34.0, rowY);
        drawCountRow(g, "Predicted", predicted, Color.rgb(110, 88, 166), x + 34.0, rowY);
    }

    private static double drawCountRow(GraphicsContext g, String label, long value, Color color, double x, double y) {
        g.setFill(color);
        g.fillRoundRect(x, y - 20.0, 16.0, 16.0, 6.0, 6.0);
        g.setFill(Color.rgb(76, 86, 98));
        g.setFont(Font.font("Arial", FontWeight.NORMAL, 18.0));
        g.fillText(label, x + 28.0, y - 5.0);
        g.setFill(Color.rgb(24, 34, 45));
        g.setFont(Font.font("Arial", FontWeight.BOLD, 30.0));
        g.fillText(String.valueOf(value), x + 28.0, y + 30.0);
        return y + 74.0;
    }

    private static void drawValidationFooter(GraphicsContext g, ResultsTable results, double x, double y) {
        g.setFill(Color.rgb(101, 111, 123));
        g.setFont(Font.font("Arial", FontWeight.NORMAL, 18.0));
        g.fillText("Rows analyzed: " + results.getCounter(), x, y);
    }

    private static List<double[]> extractLossSeries(ResultsTable trainingResults, String columnName) {
        List<double[]> series = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < trainingResults.getCounter(); rowIndex++) {
            double value = getNumericValue(trainingResults, columnName, rowIndex);
            double epochValue = getNumericValue(trainingResults, "Epoch", rowIndex);
            if (Double.isFinite(epochValue) && Double.isFinite(value)) {
                series.add(new double[]{epochValue, value});
            }
        }
        return series;
    }

    private static List<double[]> extractCsvSeries(List<Map<String, String>> rows, String xColumn, String yColumn) {
        List<double[]> series = new ArrayList<>();
        for (Map<String, String> row : rows) {
            double x = parseDouble(row.get(xColumn));
            double y = parseDouble(row.get(yColumn));
            if (Double.isFinite(x) && Double.isFinite(y)) {
                series.add(new double[]{x, y});
            }
        }
        return series;
    }

    private static double[] resolvePointBounds(List<double[]> firstSeries,
                                               List<double[]> secondSeries,
                                               int index,
                                               double fallbackMinimum,
                                               double fallbackMaximum,
                                               boolean padBounds) {
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (double[] point : combineSeries(firstSeries, secondSeries)) {
            minimum = updateMinimum(minimum, point[index]);
            maximum = updateMaximum(maximum, point[index]);
        }
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)) {
            minimum = fallbackMinimum;
            maximum = fallbackMaximum;
        }
        if (maximum <= minimum) {
            maximum = minimum + 1.0;
        }
        if (padBounds) {
            double span = maximum - minimum;
            double padding = span > 0.0 ? span * 0.08 : Math.max(0.1, maximum * 0.1);
            minimum = Math.max(0.0, minimum - padding);
            maximum = maximum + padding;
            if (maximum <= minimum) {
                maximum = minimum + 1.0;
            }
        }
        return new double[]{minimum, maximum};
    }

    private static List<double[]> combineSeries(List<double[]> firstSeries, List<double[]> secondSeries) {
        List<double[]> combined = new ArrayList<>(firstSeries.size() + secondSeries.size());
        combined.addAll(firstSeries);
        combined.addAll(secondSeries);
        return combined;
    }

    private static Map<String, String> bestScoreRow(List<Map<String, String>> rows) {
        Map<String, String> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Map<String, String> row : rows) {
            double score = parseDouble(row.get("score"));
            if (Double.isFinite(score) && score > bestScore) {
                bestScore = score;
                best = row;
            }
        }
        return best;
    }

    private static String formatLastValue(List<double[]> series) {
        return series.isEmpty() ? "NA" : String.format("%.4g", series.get(series.size() - 1)[1]);
    }

    private static String formatMinimumValue(List<double[]> series) {
        double minimum = Double.POSITIVE_INFINITY;
        for (double[] point : series) {
            minimum = updateMinimum(minimum, point[1]);
        }
        return Double.isFinite(minimum) ? String.format("%.4g", minimum) : "NA";
    }

    private static String formatNumber(String value) {
        double numeric = parseDouble(value);
        return Double.isFinite(numeric) ? String.format("%.4g", numeric) : "NA";
    }

    private static String valueOrNa(String value) {
        return value == null || value.isBlank() ? "NA" : value;
    }

    private static double meanColumn(ResultsTable table, String columnName) {
        double sum = 0.0;
        int count = 0;
        for (int row = 0; row < table.getCounter(); row++) {
            double value = getNumericValue(table, columnName, row);
            if (Double.isFinite(value)) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    private static double sumColumn(ResultsTable table, String columnName) {
        double sum = 0.0;
        for (int row = 0; row < table.getCounter(); row++) {
            double value = getNumericValue(table, columnName, row);
            if (Double.isFinite(value)) {
                sum += value;
            }
        }
        return sum;
    }

    private static double sanitizeUnitMetric(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double getNumericValue(ResultsTable table, String columnName, int row) {
        try {
            return table.getValue(columnName, row);
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double updateMinimum(double currentMinimum, double candidate) {
        return Double.isFinite(candidate) ? Math.min(currentMinimum, candidate) : currentMinimum;
    }

    private static double updateMaximum(double currentMaximum, double candidate) {
        return Double.isFinite(candidate) ? Math.max(currentMaximum, candidate) : currentMaximum;
    }

    private static double normalize(double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || !Double.isFinite(minimum) || !Double.isFinite(maximum) || maximum <= minimum) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (value - minimum) / (maximum - minimum)));
    }

    private static void saveCanvasPng(Canvas canvas, File outputFile) throws IOException {
        if (outputFile == null) {
            throw new IOException("ASTRA QC figure output file is null.");
        }
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create ASTRA QC figure directory: " + parent.getAbsolutePath());
        }
        new Scene(new Group(canvas));
        SnapshotParameters snapshotParameters = new SnapshotParameters();
        snapshotParameters.setFill(Color.WHITE);
        WritableImage writableImage = canvas.snapshot(snapshotParameters, null);
        RenderedImage renderedImage = SwingFXUtils.fromFXImage(writableImage, null);
        if (!ImageIO.write(renderedImage, "png", outputFile)) {
            throw new IOException("Could not write ASTRA QC figure image: no PNG writer available.");
        }
    }

    private static void saveOnFxThread(String figureName, FigureWriter writer) throws IOException {
        RuntimeException[] runtimeError = new RuntimeException[1];
        IOException[] ioError = new IOException[1];
        CountDownLatch latch = new CountDownLatch(1);

        FXUtils.runOnApplicationThread(() -> {
            try {
                writer.write();
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
                throw new IOException("Timed out while saving " + figureName + ".");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while saving " + figureName + ".", e);
        }

        if (runtimeError[0] != null) {
            throw runtimeError[0];
        }
        if (ioError[0] != null) {
            throw ioError[0];
        }
    }

    private static List<Map<String, String>> readCsvRows(File csvFile) throws IOException {
        if (csvFile == null || !csvFile.isFile()) {
            throw new IOException("ASTRA tuning QC figure requires an existing CSV file.");
        }
        try (BufferedReader reader = Files.newBufferedReader(csvFile.toPath(), StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                return List.of();
            }
            List<String> headers = parseCsvLine(headerLine);
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> values = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), i < values.size() ? values.get(i) : "");
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    @FunctionalInterface
    private interface FigureWriter {
        void write() throws IOException;
    }
}
