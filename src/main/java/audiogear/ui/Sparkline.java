package audiogear.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;

/** A small price-history line with an optional dashed target line. */
public final class Sparkline extends Region {

    public record Point(long epochMillis, double value) {}

    private final Canvas canvas = new Canvas();
    private List<Point> points = List.of();
    private Double target;

    public Sparkline() {
        getChildren().add(canvas);
        setPrefSize(320, 90);
        setMinHeight(70);
    }

    public void set(List<Point> pts, Double targetValue) {
        points = pts.stream().sorted((a, b) -> Long.compare(a.epochMillis(), b.epochMillis())).toList();
        target = targetValue;
        draw();
    }

    @Override protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
        draw();
    }

    private void draw() {
        double w = canvas.getWidth(), h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(Color.web("#161b22"));
        g.fillRoundRect(0, 0, w, h, 6, 6);
        g.setFont(Font.font(11));
        if (points.isEmpty()) {
            g.setFill(Color.web("#8b949e"));
            g.fillText("No prices recorded", 10, h / 2 + 4);
            return;
        }
        double lo = points.stream().mapToDouble(Point::value).min().orElse(0);
        double hi = points.stream().mapToDouble(Point::value).max().orElse(1);
        if (target != null) { lo = Math.min(lo, target); hi = Math.max(hi, target); }
        if (hi - lo < 1e-9) { lo -= 1; hi += 1; }
        long t0 = points.getFirst().epochMillis(), t1 = points.getLast().epochMillis();
        double pad = 10, top = hi, bottom = lo;
        java.util.function.DoubleUnaryOperator y = v -> pad + (top - v) / (top - bottom) * (h - 2 * pad);
        java.util.function.LongToDoubleFunction x = t -> t1 == t0 ? w / 2 : pad + (double) (t - t0) / (t1 - t0) * (w - 2 * pad);
        if (target != null) {
            g.setStroke(Color.web("#3fb950"));
            g.setLineDashes(5, 4);
            g.strokeLine(pad, y.applyAsDouble(target), w - pad, y.applyAsDouble(target));
            g.setLineDashes(null);
        }
        g.setStroke(Color.web("#58a6ff"));
        g.setLineWidth(1.8);
        g.beginPath();
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            if (i == 0) g.moveTo(x.applyAsDouble(p.epochMillis()), y.applyAsDouble(p.value()));
            else g.lineTo(x.applyAsDouble(p.epochMillis()), y.applyAsDouble(p.value()));
        }
        g.stroke();
        g.setFill(Color.web("#58a6ff"));
        for (Point p : points) g.fillOval(x.applyAsDouble(p.epochMillis()) - 2.5, y.applyAsDouble(p.value()) - 2.5, 5, 5);
    }
}
