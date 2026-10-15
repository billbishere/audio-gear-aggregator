package audiogear.ui;

import audiogear.analysis.CurveMath;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Curves on a logarithmic frequency axis, drawn on a canvas so dozens of 500-point curves stay fast.
 * Supports an optional secondary axis (impedance phase), NaN gaps, a legend and a hover read-out.
 */
public final class CurveChart extends Region {

    public record Series(String name, double[] x, double[] y, Color color, boolean dashed, boolean secondary) {
        public Series(String name, double[] x, double[] y, Color color) { this(name, x, y, color, false, false); }
    }

    /** Eight hues that stay distinguishable on the dark background and for common colour-vision deficiencies. */
    public static final List<Color> PALETTE = List.of(
            Color.web("#58a6ff"), Color.web("#f0883e"), Color.web("#3fb950"), Color.web("#f778ba"),
            Color.web("#d2a8ff"), Color.web("#e3b341"), Color.web("#56d4dd"), Color.web("#ff7b72"));

    public static Color color(int i) { return PALETTE.get(Math.floorMod(i, PALETTE.size())); }

    private static final Color BG = Color.web("#0d1117"), PLOT_BG = Color.web("#161b22"), GRID = Color.web("#30363d"),
            GRID_MINOR = Color.web("#21262d"), TEXT = Color.web("#8b949e"), TEXT_STRONG = Color.web("#c9d1d9");
    private static final double ML = 58, MT = 30, MB = 34;

    private final Canvas canvas = new Canvas();
    private List<Series> series = List.of();
    private String yLabel = "dB", y2Label;
    private Double yMin, yMax;
    private double y2Min = -90, y2Max = 90;
    private double fMin = 20, fMax = 20000;
    private boolean logY;
    private String emptyMessage = "Nothing to show yet";
    private double hoverX = -1;

    public CurveChart() {
        getChildren().add(canvas);
        getStyleClass().add("curve-chart");
        setMinSize(200, 160);
        setPrefSize(800, 420);
        canvas.setOnMouseMoved(e -> { hoverX = e.getX(); draw(); });
        canvas.setOnMouseExited(e -> { hoverX = -1; draw(); });
    }

    public void setSeries(List<Series> s) { series = List.copyOf(s); draw(); }

    /** Fixed y range; pass nulls for automatic. */
    public void setYAxis(String label, Double min, Double max) { yLabel = label; yMin = min; yMax = max; draw(); }

    /** Logarithmic y axis (useful for impedance spanning 10 to 1000 ohms). */
    public void setLogY(boolean log) { logY = log; draw(); }

    /** A right-hand axis for series flagged secondary; null label hides it. */
    public void setSecondaryAxis(String label, double min, double max) { y2Label = label; y2Min = min; y2Max = max; draw(); }

    public void setFrequencyRange(double min, double max) { fMin = min; fMax = max; draw(); }

    public void setEmptyMessage(String m) { emptyMessage = m; draw(); }

    @Override protected void layoutChildren() {
        double w = snapSizeX(getWidth()), h = snapSizeY(getHeight());
        if (canvas.getWidth() != w || canvas.getHeight() != h) {
            canvas.setWidth(w);
            canvas.setHeight(h);
            draw();
        }
    }

    private double mr() { return y2Label != null && series.stream().anyMatch(Series::secondary) ? 58 : 20; }

    private double xOf(double f, double w) {
        return ML + (Math.log(f) - Math.log(fMin)) / (Math.log(fMax) - Math.log(fMin)) * (w - ML - mr());
    }

    private double fOf(double x, double w) {
        return Math.exp(Math.log(fMin) + (x - ML) / (w - ML - mr()) * (Math.log(fMax) - Math.log(fMin)));
    }

    private double tf(double v) { return logY ? Math.log10(Math.max(v, 1e-9)) : v; }

    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 10 || h < 10) return;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(BG);
        g.fillRect(0, 0, w, h);
        double pw = w - ML - mr(), ph = h - MT - MB;
        g.setFill(PLOT_BG);
        g.fillRect(ML, MT, pw, ph);

        double[] range = yRange();
        double lo = range[0], hi = range[1], step = range[2];
        g.setFont(Font.font(11));

        // frequency grid
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.TOP);
        for (double decade = 1; decade <= 100000; decade *= 10) {
            for (int m = 1; m <= 9; m++) {
                double f = m * decade;
                if (f < fMin * 0.999 || f > fMax * 1.001) continue;
                double x = xOf(f, w);
                boolean major = m == 1 || m == 2 || m == 5;
                g.setStroke(major ? GRID : GRID_MINOR);
                g.setLineWidth(1);
                g.strokeLine(x, MT, x, MT + ph);
                if (major) {
                    g.setFill(TEXT);
                    g.fillText(f >= 1000 ? Fx.num(f / 1000, 1) + "k" : Fx.num(f, 0), x, MT + ph + 6);
                }
            }
        }
        g.setTextAlign(TextAlignment.RIGHT);
        g.fillText("Hz", w - mr(), MT + ph + 18);

        // value grid
        g.setTextBaseline(VPos.CENTER);
        for (double v = Math.ceil(lo / step) * step; v <= hi + step * 1e-6; v += step) {
            double y = MT + (hi - v) / (hi - lo) * ph;
            g.setStroke(Math.abs(v) < step * 1e-6 && !logY ? TEXT : GRID);
            g.strokeLine(ML, y, ML + pw, y);
            g.setFill(TEXT);
            g.fillText(logY ? Fx.num(Math.pow(10, v), 1) : Fx.num(v, 2), ML - 6, y);
        }
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        g.setFill(TEXT_STRONG);
        g.fillText(yLabel, 6, 8);

        boolean secondary = y2Label != null && series.stream().anyMatch(Series::secondary);
        if (secondary) {
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.CENTER);
            g.setFill(TEXT);
            double s2 = niceStep((y2Max - y2Min) / 6);
            for (double v = Math.ceil(y2Min / s2) * s2; v <= y2Max + 1e-9; v += s2) {
                g.fillText(Fx.num(v, 1), ML + pw + 6, MT + (y2Max - v) / (y2Max - y2Min) * ph);
            }
            g.setTextAlign(TextAlignment.RIGHT);
            g.setTextBaseline(VPos.TOP);
            g.setFill(TEXT_STRONG);
            g.fillText(y2Label, w - 4, 8);
        }

        if (series.isEmpty()) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.setFill(TEXT);
            g.setFont(Font.font(14));
            g.fillText(emptyMessage, ML + pw / 2, MT + ph / 2);
            return;
        }

        g.save();
        g.beginPath();
        g.rect(ML, MT, pw, ph);
        g.clip();
        for (Series s : series) {
            g.setStroke(s.color());
            g.setLineWidth(1.7);
            g.setLineDashes(s.dashed() ? new double[]{6, 4} : null);
            double a = s.secondary() ? y2Max : hi, b = s.secondary() ? y2Min : lo;
            boolean pen = false;
            g.beginPath();
            for (int i = 0; i < s.x().length; i++) {
                double f = s.x()[i], v = s.y()[i];
                if (Double.isNaN(v) || f <= 0) { pen = false; continue; }
                double tv = s.secondary() ? v : tf(v);
                double x = xOf(f, w), y = MT + (a - tv) / (a - b) * ph;
                if (pen) g.lineTo(x, y);
                else { g.moveTo(x, y); pen = true; }
            }
            g.stroke();
        }
        g.setLineDashes(null);
        g.restore();

        drawLegend(g, w);
        if (hoverX >= ML && hoverX <= ML + pw) drawHover(g, w, ph);
    }

    private void drawLegend(GraphicsContext g, double w) {
        int n = Math.min(series.size(), 12);
        double lw = 0;
        g.setFont(Font.font(11));
        for (int i = 0; i < n; i++) lw = Math.max(lw, textWidth(series.get(i).name()));
        double boxW = Math.min(lw + 34, w * 0.45), boxH = n * 16 + 8 + (series.size() > n ? 14 : 0);
        double x0 = w - mr() - boxW - 8, y0 = MT + 8;
        g.setFill(Color.color(0.05, 0.07, 0.09, 0.82));
        g.fillRoundRect(x0, y0, boxW, boxH, 6, 6);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        for (int i = 0; i < n; i++) {
            Series s = series.get(i);
            double y = y0 + 12 + i * 16;
            g.setStroke(s.color());
            g.setLineWidth(2);
            g.setLineDashes(s.dashed() ? new double[]{4, 3} : null);
            g.strokeLine(x0 + 8, y, x0 + 24, y);
            g.setLineDashes(null);
            g.setFill(TEXT_STRONG);
            g.fillText(clip(s.name(), boxW - 34), x0 + 30, y);
        }
        if (series.size() > n) {
            g.setFill(TEXT);
            g.fillText("+" + (series.size() - n) + " more", x0 + 30, y0 + 12 + n * 16);
        }
    }

    private void drawHover(GraphicsContext g, double w, double ph) {
        double f = fOf(hoverX, w);
        g.setStroke(Color.web("#8b949e", 0.7));
        g.setLineWidth(1);
        g.strokeLine(hoverX, MT, hoverX, MT + ph);
        List<String> lines = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        lines.add(f >= 1000 ? Fx.num(f / 1000, 2) + " kHz" : Fx.num(f, 1) + " Hz");
        colors.add(TEXT_STRONG);
        for (Series s : series) {
            if (lines.size() > 13) break;
            double v = CurveMath.valueAt(s.x(), s.y(), f);
            if (Double.isNaN(v)) continue;
            lines.add(clip(s.name(), 220) + ": " + Fx.num(v, s.secondary() ? 1 : 2));
            colors.add(s.color());
        }
        g.setFont(Font.font("System", FontWeight.NORMAL, 11));
        double bw = 0;
        for (String l : lines) bw = Math.max(bw, textWidth(l));
        bw += 16;
        double bh = lines.size() * 15 + 8;
        double bx = hoverX + 12 + bw > w ? hoverX - 12 - bw : hoverX + 12;
        double by = MT + 8;
        g.setFill(Color.color(0.02, 0.03, 0.05, 0.9));
        g.fillRoundRect(bx, by, bw, bh, 6, 6);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.TOP);
        for (int i = 0; i < lines.size(); i++) {
            g.setFill(colors.get(i));
            g.fillText(lines.get(i), bx + 8, by + 4 + i * 15);
        }
    }

    /** {lo, hi, step} in axis units (log10 for a log axis). */
    private double[] yRange() {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Series s : series) {
            if (s.secondary()) continue;
            for (int i = 0; i < s.y().length; i++) {
                double v = s.y()[i], f = s.x()[i];
                if (Double.isNaN(v) || f < fMin || f > fMax) continue;
                double t = tf(v);
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
            }
        }
        if (yMin != null) lo = tf(yMin);
        if (yMax != null) hi = tf(yMax);
        if (!Double.isFinite(lo) || !Double.isFinite(hi)) { lo = logY ? 1 : -10; hi = logY ? 3 : 10; }
        if (hi - lo < (logY ? 0.2 : 2)) { double mid = (hi + lo) / 2; lo = mid - (logY ? 0.1 : 1); hi = mid + (logY ? 0.1 : 1); }
        double step = logY ? Math.max(0.1, niceStep((hi - lo) / 5)) : niceStep((hi - lo) / 7);
        if (yMin == null) lo = Math.floor((lo - step * 0.25) / step) * step;
        if (yMax == null) hi = Math.ceil((hi + step * 0.25) / step) * step;
        return new double[]{lo, hi, step};
    }

    static double niceStep(double raw) {
        if (raw <= 0 || Double.isNaN(raw)) return 1;
        double p = Math.pow(10, Math.floor(Math.log10(raw)));
        double m = raw / p;
        return (m < 1.5 ? 1 : m < 3 ? 2 : m < 7 ? 5 : 10) * p;
    }

    private static double textWidth(String s) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(s);
        t.setFont(Font.font(11));
        return t.getLayoutBounds().getWidth();
    }

    private static String clip(String s, double maxWidth) {
        if (textWidth(s) <= maxWidth) return s;
        String t = s;
        while (t.length() > 4 && textWidth(t + "…") > maxWidth) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
