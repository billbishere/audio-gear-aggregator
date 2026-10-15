package audiogear.ui.dialogs;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

/** A two-pairs-per-row form: label, field, label, field. */
final class FormGrid extends GridPane {

    private int row, col;

    FormGrid() {
        setHgap(10);
        setVgap(8);
        setPadding(new Insets(4, 0, 8, 0));
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(120);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        field.setMinWidth(160);
        getColumnConstraints().addAll(label, field, label, field);
    }

    FormGrid add(String label, Node field) {
        Label l = new Label(label);
        l.getStyleClass().add("spec-key");
        add(l, col * 2, row);
        add(field, col * 2 + 1, row);
        if (field instanceof javafx.scene.layout.Region r) r.setMaxWidth(Double.MAX_VALUE);
        if (++col == 2) { col = 0; row++; }
        return this;
    }

    /** A field spanning the full width on its own row. */
    FormGrid wide(String label, Node field) {
        if (col != 0) { col = 0; row++; }
        Label l = new Label(label);
        l.getStyleClass().add("spec-key");
        add(l, 0, row);
        add(field, 1, row, 3, 1);
        if (field instanceof javafx.scene.layout.Region r) r.setMaxWidth(Double.MAX_VALUE);
        row++;
        return this;
    }
}
