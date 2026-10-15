package audiogear.ui.dialogs;

import audiogear.domain.Channel;
import audiogear.domain.Curve;
import audiogear.domain.CurveKind;
import audiogear.domain.GearSummary;
import audiogear.importers.CurveFileParser;
import audiogear.ui.Fx;
import audiogear.ui.GearPicker;
import javafx.beans.binding.Bindings;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** After a curve file has parsed, choose which gear it belongs to and what it measures. */
public final class CurveImportDialog extends Dialog<Curve> {

    public CurveImportDialog(Path file, CurveFileParser.Parsed parsed, Supplier<List<GearSummary>> catalog, Long gearId) {
        setTitle("Import curve");
        setHeaderText(file.getFileName() + " — " + parsed.size() + " points, " + Fx.num(parsed.freqHz()[0], 1) + " Hz to "
                + Fx.num(parsed.freqHz()[parsed.size() - 1], 0) + " Hz" + (parsed.phaseDeg() != null ? ", with phase" : ""));
        GearPicker gear = new GearPicker("Search the catalog", catalog, g -> g.type().isTransducer());
        gear.selectId(gearId);
        ComboBox<CurveKind> kind = new ComboBox<>();
        kind.getItems().setAll(CurveKind.FR, CurveKind.IMPEDANCE);
        kind.setConverter(Fx.converter(k -> k == CurveKind.FR ? "Frequency response (dB)" : "Impedance (Ω)"));
        double median = parsed.value()[parsed.size() / 2];
        kind.setValue(parsed.phaseDeg() != null || median > 4 && median < 2000 && min(parsed.value()) > 0 ? CurveKind.IMPEDANCE : CurveKind.FR);
        ComboBox<Channel> channel = Fx.enumCombo(Channel.class, null);
        channel.setValue(Channel.AVG);
        TextField source = new TextField("user");
        String base = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        TextField label = new TextField(base);

        FormGrid g = new FormGrid().wide("Gear *", gear).add("Measures", kind).add("Channel", channel)
                .add("Source", source).add("Label", label);
        getDialogPane().setContent(new VBox(6, g,
                Fx.muted("Source is who measured it (e.g. your name or a reviewer); label tells curves of the same gear apart.")));
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(Bindings.createBooleanBinding(
                () -> gear.getValue() == null || source.getText().isBlank(), gear.valueProperty(), source.textProperty()));
        setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            boolean z = kind.getValue() == CurveKind.IMPEDANCE;
            return new Curve(null, gear.getValue().id(), kind.getValue(), source.getText().strip(), label.getText().strip(),
                    channel.getValue(), parsed.freqHz(), parsed.value(), z ? parsed.phaseDeg() : null, file.toString(), null);
        });
    }

    private static double min(double[] v) {
        double m = Double.POSITIVE_INFINITY;
        for (double x : v) m = Math.min(m, x);
        return m;
    }
}
