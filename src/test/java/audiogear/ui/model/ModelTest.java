package audiogear.ui.model;

import audiogear.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    private static GearSummary summary(long id, GearType type, String brand, String model, Double impedance, DriverType driver,
                                       Form form, String tags, Double msrp, Double best) {
        return new GearSummary(id, type, brand, model, "", GearStatus.NONE, msrp, "USD", null, form, null, driver, "1DD",
                impedance, null, null, null, null, null, type.isDac() ? "ES9038" : null, 0, 0, 0, tags, best, best == null ? null : "USD");
    }

    @Test
    void filterCombinesCriteria() {
        var hd650 = summary(1, GearType.HEADPHONE, "Sennheiser", "HD 650", 300.0, DriverType.DYNAMIC, Form.OVER_EAR, "home, reference", 499.0, 380.0);
        var aria = summary(2, GearType.IEM, "Moondrop", "Aria", 32.0, DriverType.DYNAMIC, Form.IN_EAR, null, 80.0, null);
        var dx5 = summary(3, GearType.AMP_DAC, "Topping", "DX5", null, null, null, "desk", 499.0, null);

        assertTrue(CatalogFilter.none().test(hd650));
        var amps = new CatalogFilter(CatalogFilter.Tab.AMPS, null, null, null, null, null, null, null, null, null);
        var dacs = new CatalogFilter(CatalogFilter.Tab.DACS, null, null, null, null, null, null, null, null, null);
        assertTrue(amps.test(dx5) && dacs.test(dx5));
        assertFalse(amps.test(hd650));

        var text = new CatalogFilter(CatalogFilter.Tab.ALL, "senn hd-650", null, null, null, null, null, null, null, null);
        assertTrue(text.test(hd650));
        assertFalse(text.test(aria));
        assertTrue(new CatalogFilter(CatalogFilter.Tab.ALL, "es9038", null, null, null, null, null, null, null, null).test(dx5));

        var lowZ = new CatalogFilter(CatalogFilter.Tab.ALL, null, null, null, null, null, 64.0, null, null, null);
        assertTrue(lowZ.test(aria));
        assertFalse(lowZ.test(hd650));
        assertFalse(lowZ.test(dx5)); // no impedance -> excluded when a range is set

        var cheap = new CatalogFilter(CatalogFilter.Tab.ALL, null, null, null, null, null, null, null, 400.0, null);
        assertTrue(cheap.test(hd650)); // best price 380 beats MSRP 499
        assertFalse(cheap.test(dx5));

        var tagged = new CatalogFilter(CatalogFilter.Tab.ALL, null, null, null, null, null, null, null, null, "Reference");
        assertTrue(tagged.test(hd650));
        assertFalse(tagged.test(dx5));
        assertFalse(new CatalogFilter(CatalogFilter.Tab.ALL, null, null, null, Form.IN_EAR, null, null, null, null, null).test(hd650));
    }

    private static CompareModel.Item headphone(String model, double z, double sens, SensitivityUnit unit, Double weight) {
        Gear g = new Gear(1L, GearType.HEADPHONE, "X", model, "", null, null, null, GearStatus.NONE, null, null, null);
        TransducerSpec t = new TransducerSpec(1L, Form.OVER_EAR, Back.OPEN, DriverType.DYNAMIC, null, null, z, sens, unit, weight, null);
        GearSummary s = summary(1, GearType.HEADPHONE, "X", model, z, DriverType.DYNAMIC, Form.OVER_EAR, null, null, null);
        return new CompareModel.Item(new GearDetail(g, t, null, List.of(), null, List.of()), s);
    }

    @Test
    void compareMarksBestAndHidesSameRows() {
        var a = headphone("A", 300, 97, SensitivityUnit.DB_PER_MW, 260.0);
        var b = headphone("B", 32, 110, SensitivityUnit.DB_PER_V, 380.0);
        List<CompareModel.Row> rows = CompareModel.rows(List.of(a, b), false);

        CompareModel.Row weight = rows.stream().filter(r -> r.label().equals("Weight")).findFirst().orElseThrow();
        assertEquals(0, weight.best()); // lighter wins
        CompareModel.Row sens = rows.stream().filter(r -> r.label().startsWith("Sensitivity")).findFirst().orElseThrow();
        assertEquals(List.of("102.2 dB/V", "110 dB/V"), sens.values());
        CompareModel.Row drive = rows.stream().filter(r -> r.label().startsWith("Power for")).findFirst().orElseThrow();
        assertEquals(0, drive.best()); // A needs 19.95 mW; B (110 dB/V into 32 ohm = 1 V) needs 31.25 mW
        assertEquals("19.95 mW", drive.values().get(0));
        assertEquals("31.25 mW", drive.values().get(1));

        assertTrue(rows.stream().noneMatch(r -> r.section().equals("Amplifier"))); // all-empty sections are dropped
        assertTrue(rows.stream().anyMatch(r -> r.label().equals("Form")));
        assertTrue(CompareModel.rows(List.of(a, b), true).stream().noneMatch(r -> r.label().equals("Form")));
    }

    @Test
    void bestNeedsTwoDifferentValues() {
        assertEquals(-1, CompareModel.best(List.of(1.0, 1.0), CompareModel.Better.LOWER));
        assertEquals(-1, CompareModel.best(java.util.Arrays.asList(1.0, null), CompareModel.Better.LOWER));
        assertEquals(2, CompareModel.best(List.of(1.0, 3.0, 5.0), CompareModel.Better.HIGHER));
    }
}
