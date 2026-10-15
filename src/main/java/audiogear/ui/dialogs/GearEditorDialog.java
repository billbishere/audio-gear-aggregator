package audiogear.ui.dialogs;

import atlantafx.base.theme.Styles;
import audiogear.domain.*;
import audiogear.ui.Fx;
import audiogear.ui.NumberField;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Create or edit a gear item. Sections follow the chosen type; OK stays disabled until the form is valid. */
public final class GearEditorDialog extends Dialog<GearDetail> {

    private final GearDetail initial;
    private final ComboBox<GearType> type = new ComboBox<>();
    private final TextField brand = new TextField(), model = new TextField(), variant = new TextField();
    private final ComboBox<GearStatus> status = Fx.enumCombo(GearStatus.class, null);
    private final NumberField year = new NumberField("e.g. 2021", 1900.0, false);
    private final NumberField msrp = NumberField.nonNegative("price");
    private final TextField currency = new TextField(), tags = new TextField();
    private final TextArea notes = new TextArea();

    private final ComboBox<Form> form = Fx.enumCombo(Form.class, Fx.DASH);
    private final ComboBox<Back> back = Fx.enumCombo(Back.class, Fx.DASH);
    private final ComboBox<DriverType> driver = Fx.enumCombo(DriverType.class, Fx.DASH);
    private final TextField driverConfig = new TextField(), connector = new TextField();
    private final NumberField driverSize = NumberField.positive("mm"), impedance = NumberField.positive("Ω");
    private final NumberField sensitivity = new NumberField("e.g. 97"), weight = NumberField.positive("g");
    private final ComboBox<SensitivityUnit> sensUnit = Fx.enumCombo(SensitivityUnit.class, null);

    private final NumberField outZ = NumberField.nonNegative("Ω"), maxV = NumberField.positive("Vrms"), maxI = NumberField.positive("mA");
    private final TextField gain = new TextField(), topology = new TextField(), ampOutputs = new TextField();
    private final VBox powerRows = new VBox(6);
    private final List<PowerRow> powers = new ArrayList<>();
    private final SimpleIntegerProperty powerVersion = new SimpleIntegerProperty();

    private final TextField chip = new TextField(), dacInputs = new TextField(), dacOutputs = new TextField();
    private final NumberField sinad = new NumberField("dB"), outV = NumberField.positive("Vrms"), maxRate = NumberField.positive("kHz");
    private final CheckBox dsd = new CheckBox("DSD supported");

    private final List<NumberField> numbers = List.of(year, msrp, driverSize, impedance, sensitivity, weight, outZ, maxV, maxI, sinad, outV, maxRate);

    private record PowerRow(HBox box, ComboBox<AmpOutput> output, NumberField load, NumberField mw) {}

    public GearEditorDialog(GearDetail detail) {
        this.initial = detail;
        Gear g = detail.gear();
        setTitle(g.id() == null ? "New " + Enums.label(g.type()) : "Edit " + g.displayName());
        setHeaderText(null);
        setResizable(true);

        type.getItems().setAll(GearType.values());
        type.setConverter(Fx.converter(Enums::label));
        type.setValue(g.type());
        brand.setText(g.brand());
        model.setText(g.model());
        variant.setText(g.variant());
        variant.setPromptText("optional, e.g. 2020 revision");
        status.setValue(g.status());
        year.setValue(g.releaseYear());
        msrp.setValue(g.msrp());
        currency.setText(g.msrpCurrency());
        currency.setPromptText("USD");
        currency.setPrefColumnCount(5);
        tags.setText(String.join(", ", detail.tags()));
        tags.setPromptText("comma separated, e.g. portable, work");
        notes.setText(g.notes());
        notes.setPrefRowCount(3);
        notes.setWrapText(true);

        TransducerSpec t = detail.transducer() != null ? detail.transducer() : Specs.emptyTransducer(g.id());
        form.setValue(t.form());
        back.setValue(t.back());
        driver.setValue(t.driverType());
        driverConfig.setText(t.driverConfig());
        driverConfig.setPromptText("e.g. 1DD+4BA");
        driverSize.setValue(t.driverSizeMm());
        impedance.setValue(t.impedanceOhm());
        sensitivity.setValue(t.sensitivity());
        sensUnit.setValue(t.sensitivityUnit() == null ? SensitivityUnit.DB_PER_MW : t.sensitivityUnit());
        weight.setValue(t.weightG());
        connector.setText(t.connector());
        connector.setPromptText("e.g. 2-pin 0.78, MMCX, dual 3.5");

        AmpSpec a = detail.amp() != null ? detail.amp() : Specs.emptyAmp(g.id());
        outZ.setValue(a.outputImpedanceOhm());
        maxV.setValue(a.maxVoltageVrms());
        maxI.setValue(a.maxCurrentMa());
        gain.setText(a.gainSettings());
        topology.setText(a.topology());
        ampOutputs.setText(a.outputs());
        for (AmpPower p : detail.powers()) addPower(p);

        DacSpec d = detail.dac() != null ? detail.dac() : Specs.emptyDac(g.id());
        chip.setText(d.chip());
        sinad.setValue(d.sinadDb());
        outV.setValue(d.outputVoltageVrms());
        maxRate.setValue(d.maxSampleRateKhz());
        dsd.setAllowIndeterminate(true);
        if (d.dsd() == null) dsd.setIndeterminate(true);
        else dsd.setSelected(d.dsd());
        dacInputs.setText(d.inputs());
        dacOutputs.setText(d.outputs());

        VBox general = Fx.section("General", new FormGrid()
                .add("Type", type).add("Status", status)
                .add("Brand *", brand).add("Model *", model)
                .add("Variant", variant).add("Release year", year)
                .add("MSRP", msrp).add("Currency", currency)
                .wide("Tags", tags).wide("Notes", notes));
        VBox transducer = Fx.section("Headphone / IEM", new FormGrid()
                .add("Form", form).add("Back", back)
                .add("Driver type", driver).add("Driver layout", driverConfig)
                .add("Driver size (mm)", driverSize).add("Impedance (Ω)", impedance)
                .add("Sensitivity", sensitivity).add("Sensitivity unit", sensUnit)
                .add("Weight (g)", weight).add("Connector", connector));
        Button addPower = new Button("Add power rating");
        addPower.getStyleClass().add(Styles.SMALL);
        addPower.setOnAction(e -> addPower(new AmpPower(null, null, AmpOutput.SE, 32, 100)));
        VBox amp = Fx.section("Amplifier", new FormGrid()
                .add("Output impedance (Ω)", outZ).add("Max voltage (Vrms)", maxV)
                .add("Max current (mA)", maxI).add("Gain settings", gain)
                .add("Topology", topology).add("Outputs", ampOutputs),
                Fx.muted("Rated power: what the maker quotes per load, e.g. 1000 mW into 32 Ω from the balanced output."),
                powerRows, addPower);
        VBox dac = Fx.section("DAC", new FormGrid()
                .add("Chip", chip).add("SINAD (dB)", sinad)
                .add("Output voltage (Vrms)", outV).add("Max sample rate (kHz)", maxRate)
                .add("Inputs", dacInputs).add("Outputs", dacOutputs)
                .add("", dsd));

        Runnable sync = () -> {
            GearType ty = type.getValue();
            show(transducer, ty.isTransducer());
            show(amp, ty.isAmp());
            show(dac, ty.isDac());
        };
        type.valueProperty().addListener((o, x, y) -> sync.run());

        VBox body = new VBox(12, general, transducer, amp, dac);
        body.setPadding(new Insets(4));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(760);
        scroll.setPrefViewportHeight(620);
        getDialogPane().setContent(scroll);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        show(transducer, g.type().isTransducer());
        show(amp, g.type().isAmp());
        show(dac, g.type().isDac());

        BooleanBinding invalid = Bindings.createBooleanBinding(() -> !isValid(),
                brand.textProperty(), model.textProperty(), powerVersion,
                year.textProperty(), msrp.textProperty(), driverSize.textProperty(), impedance.textProperty(),
                sensitivity.textProperty(), weight.textProperty(), outZ.textProperty(), maxV.textProperty(),
                maxI.textProperty(), sinad.textProperty(), outV.textProperty(), maxRate.textProperty(), type.valueProperty());
        getDialogPane().lookupButton(ButtonType.OK).disableProperty().bind(invalid);
        setResultConverter(b -> b == ButtonType.OK ? build() : null);
    }

    private static void show(VBox v, boolean visible) {
        v.setVisible(visible);
        v.setManaged(visible);
    }

    private void addPower(AmpPower p) {
        ComboBox<AmpOutput> out = Fx.enumCombo(AmpOutput.class, null);
        out.setValue(p.output());
        NumberField load = NumberField.positive("Ω"), mw = NumberField.positive("mW");
        load.setValue(p.loadOhm());
        mw.setValue(p.powerMw());
        Button remove = new Button("Remove");
        remove.getStyleClass().addAll(Styles.SMALL, Styles.FLAT);
        HBox box = Fx.row(8, out, new Label("into"), load, new Label("Ω  →"), mw, new Label("mW"), remove);
        PowerRow row = new PowerRow(box, out, load, mw);
        remove.setOnAction(e -> {
            powers.remove(row);
            powerRows.getChildren().remove(box);
            powerVersion.set(powerVersion.get() + 1);
        });
        load.textProperty().addListener((o, a, b) -> powerVersion.set(powerVersion.get() + 1));
        mw.textProperty().addListener((o, a, b) -> powerVersion.set(powerVersion.get() + 1));
        powers.add(row);
        powerRows.getChildren().add(box);
        powerVersion.set(powerVersion.get() + 1);
    }

    private boolean isValid() {
        if (brand.getText() == null || brand.getText().isBlank() || model.getText() == null || model.getText().isBlank()) return false;
        if (!numbers.stream().allMatch(NumberField::isValid)) return false;
        if (type.getValue().isAmp()) {
            for (PowerRow r : powers) if (r.load().getValue() == null || r.mw().getValue() == null) return false;
        }
        return true;
    }

    private static String s(TextField f) {
        return f.getText() == null || f.getText().isBlank() ? null : f.getText().strip();
    }

    private GearDetail build() {
        Gear old = initial.gear();
        GearType ty = type.getValue();
        Gear g = new Gear(old.id(), ty, brand.getText().strip(), model.getText().strip(), s(variant), year.getIntValue(),
                msrp.getValue(), s(currency), status.getValue(), notes.getText() == null || notes.getText().isBlank() ? null : notes.getText().strip(),
                old.createdAt(), old.updatedAt());
        TransducerSpec t = ty.isTransducer() ? new TransducerSpec(old.id(), form.getValue(), back.getValue(), driver.getValue(),
                s(driverConfig), driverSize.getValue(), impedance.getValue(), sensitivity.getValue(),
                sensitivity.getValue() == null ? null : sensUnit.getValue(), weight.getValue(), s(connector)) : null;
        AmpSpec a = ty.isAmp() ? new AmpSpec(old.id(), outZ.getValue(), maxV.getValue(), maxI.getValue(), s(gain), s(topology), s(ampOutputs)) : null;
        List<AmpPower> p = new ArrayList<>();
        if (ty.isAmp()) for (PowerRow r : powers) p.add(new AmpPower(null, old.id(), r.output().getValue(), r.load().getValue(), r.mw().getValue()));
        DacSpec d = ty.isDac() ? new DacSpec(old.id(), s(chip), sinad.getValue(), outV.getValue(), maxRate.getValue(),
                dsd.isIndeterminate() ? null : dsd.isSelected(), s(dacInputs), s(dacOutputs)) : null;
        List<String> tagList = tags.getText() == null || tags.getText().isBlank() ? List.of()
                : Arrays.stream(tags.getText().split(",")).map(String::strip).filter(x -> !x.isEmpty()).toList();
        return new GearDetail(g, t, a, p, d, tagList);
    }
}
