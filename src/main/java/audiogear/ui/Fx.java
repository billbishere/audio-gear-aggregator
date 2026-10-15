package audiogear.ui;

import atlantafx.base.theme.Styles;
import audiogear.domain.Enums;
import audiogear.importers.ImportSummary;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Small UI helpers shared by the screens. */
public final class Fx {
    private Fx() {}

    public static final String DASH = "—";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    /** Number with up to `decimals` decimals, trailing zeros removed; "—" for null/NaN. */
    public static String num(Double v, int decimals) {
        if (v == null || v.isNaN()) return DASH;
        String s = String.format(Locale.ROOT, "%." + decimals + "f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.equals("-0") ? "0" : s;
    }

    public static String num(Double v, int decimals, String unit) {
        return v == null || v.isNaN() ? DASH : num(v, decimals) + " " + unit;
    }

    public static String money(Double v, String currency) {
        if (v == null) return DASH;
        return String.format(Locale.ROOT, "%,.2f", v).replace(".00", "") + (currency == null ? "" : " " + currency);
    }

    /** "2026-09-01" in local time, or "" when unknown. */
    public static String date(String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) return "";
        try {
            return DATE.format(Instant.parse(isoInstant));
        } catch (RuntimeException e) {
            return isoInstant.length() >= 10 ? isoInstant.substring(0, 10) : isoInstant;
        }
    }

    public static String text(String s) { return s == null || s.isBlank() ? DASH : s; }

    public static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add(Styles.TITLE_3);
        return l;
    }

    public static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add(Styles.TITLE_4);
        return l;
    }

    public static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add(Styles.TEXT_MUTED);
        l.setWrapText(true);
        return l;
    }

    public static Region grow() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static HBox row(double spacing, Node... nodes) {
        HBox h = new HBox(spacing, nodes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    public static VBox section(String heading, Node... content) {
        VBox v = new VBox(8);
        v.getChildren().add(heading(heading));
        v.getChildren().addAll(content);
        v.getStyleClass().add("section");
        v.setPadding(new Insets(12));
        return v;
    }

    /** ComboBox over an enum with readable labels; when nullable, the first item (null) shows nullLabel. */
    public static <E extends Enum<E>> ComboBox<E> enumCombo(Class<E> type, String nullLabel) {
        List<E> items = new ArrayList<>();
        if (nullLabel != null) items.add(null);
        items.addAll(Arrays.asList(type.getEnumConstants()));
        ComboBox<E> c = new ComboBox<>();
        c.getItems().setAll(items);
        StringConverter<E> conv = converter(e -> e == null ? (nullLabel == null ? "" : nullLabel) : Enums.label(e));
        c.setConverter(conv);
        c.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(E item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? (nullLabel == null ? "" : nullLabel) : conv.toString(item));
            }
        });
        if (nullLabel != null) c.getSelectionModel().selectFirst();
        return c;
    }

    public static <T> StringConverter<T> converter(Function<T, String> f) {
        return new StringConverter<>() {
            @Override public String toString(T t) { return f.apply(t); }
            @Override public T fromString(String s) { throw new UnsupportedOperationException(); }
        };
    }

    public static void error(String header, String content) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setHeaderText(header);
        a.setContentText(content);
        a.showAndWait();
    }

    public static boolean confirm(String header, String content) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, content, ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(header);
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    /** Shows an import's headline, with the skipped items in an expandable list. */
    public static void showSummary(String title, ImportSummary s) {
        Alert a = new Alert(s.skipped() == 0 ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        a.setTitle(title);
        a.setHeaderText(s.headline());
        if (!s.problems().isEmpty()) {
            TextArea area = new TextArea(String.join("\n", s.problems()));
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(12);
            a.getDialogPane().setExpandableContent(area);
            a.getDialogPane().setExpanded(s.problems().size() <= 20);
            a.setContentText(s.problems().size() + " item(s) were skipped. Details below.");
        }
        a.showAndWait();
    }

    public static void savePng(Node node, File file) throws IOException {
        WritableImage img = node.snapshot(null, null);
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", file);
    }
}
