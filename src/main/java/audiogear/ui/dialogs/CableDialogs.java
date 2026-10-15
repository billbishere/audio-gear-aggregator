package audiogear.ui.dialogs;

import atlantafx.base.theme.Styles;
import audiogear.app.AppContext;
import audiogear.domain.Cable;
import audiogear.ui.Fx;
import audiogear.ui.NumberField;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.Optional;

/** Cable list management (used from the Pairing screen). */
public final class CableDialogs {
    private CableDialogs() {}

    /** Shows the cable manager; onChange runs after any save or delete. */
    public static void manage(AppContext ctx, Runnable onChange) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle("Cables");
        d.setHeaderText("Cable resistance adds to the amp's output impedance in the pairing check.");
        TableView<Cable> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(col("Name", Cable::name));
        table.getColumns().add(col("Plugs", c -> Fx.text(c.sourcePlug()) + " → " + Fx.text(c.devicePlug())));
        table.getColumns().add(col("Length", c -> Fx.num(c.lengthM(), 2, "m")));
        table.getColumns().add(col("Loop resistance", c -> Fx.num(c.loopResistanceOhm(), 3, "Ω")));
        table.getColumns().add(col("Owned", c -> c.owned() ? "Yes" : ""));
        table.setPrefSize(640, 300);
        Runnable reload = () -> ctx.run(null, ctx.cables::list, table.getItems()::setAll);
        reload.run();

        Button add = new Button("Add…"), edit = new Button("Edit…"), del = new Button("Delete");
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        del.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        del.getStyleClass().add(Styles.DANGER);
        add.setOnAction(e -> edit(null).ifPresent(c -> ctx.run("Saving cable", () -> ctx.cables.save(c), id -> { reload.run(); onChange.run(); })));
        edit.setOnAction(e -> edit(table.getSelectionModel().getSelectedItem())
                .ifPresent(c -> ctx.run("Saving cable", () -> ctx.cables.save(c), id -> { reload.run(); onChange.run(); })));
        del.setOnAction(e -> {
            Cable c = table.getSelectionModel().getSelectedItem();
            if (Fx.confirm("Delete " + c.name() + "?", "This can't be undone."))
                ctx.run("Deleting cable", () -> { ctx.cables.delete(c.id()); return null; }, x -> { reload.run(); onChange.run(); });
        });
        d.getDialogPane().setContent(new VBox(8, table, Fx.row(8, add, edit, del)));
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    private static TableColumn<Cable, String> col(String title, java.util.function.Function<Cable, String> f) {
        TableColumn<Cable, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        return c;
    }

    static Optional<Cable> edit(Cable existing) {
        Dialog<Cable> d = new Dialog<>();
        d.setTitle(existing == null ? "New cable" : "Edit cable");
        TextField name = new TextField(existing == null ? "" : existing.name());
        ComboBox<String> source = new ComboBox<>();
        source.setEditable(true);
        source.getItems().setAll("3.5 mm", "4.4 mm", "6.35 mm", "XLR4", "2.5 mm");
        source.getEditor().setText(existing == null ? "" : Fx.text(existing.sourcePlug()).replace(Fx.DASH, ""));
        TextField device = new TextField(existing == null || existing.devicePlug() == null ? "" : existing.devicePlug());
        device.setPromptText("e.g. 2-pin 0.78, MMCX, mini-XLR");
        NumberField length = NumberField.positive("m"), resistance = NumberField.nonNegative("Ω");
        TextField conductor = new TextField(existing == null || existing.conductor() == null ? "" : existing.conductor());
        CheckBox owned = new CheckBox("I own this cable");
        if (existing != null) {
            length.setValue(existing.lengthM());
            resistance.setValue(existing.loopResistanceOhm());
            owned.setSelected(existing.owned());
        }
        FormGrid g = new FormGrid().add("Name *", name).add("Owned", owned)
                .add("Amp-side plug", source).add("Headphone-side plug", device)
                .add("Length (m)", length).add("Loop resistance (Ω)", resistance)
                .wide("Conductor", conductor);
        d.getDialogPane().setContent(new VBox(6, g, Fx.muted("Loop resistance is the round trip (signal + return). Most cables are 0.1–0.5 Ω.")));
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(Bindings.createBooleanBinding(
                () -> name.getText().isBlank() || !length.isValid() || !resistance.isValid(),
                name.textProperty(), length.textProperty(), resistance.textProperty()));
        d.setResultConverter(b -> b != ButtonType.OK ? null : new Cable(existing == null ? null : existing.id(),
                existing == null ? null : existing.gearId(), name.getText().strip(), blank(source.getEditor().getText()),
                blank(device.getText()), length.getValue(), blank(conductor.getText()), resistance.getValue(), owned.isSelected()));
        return d.showAndWait();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }
}
