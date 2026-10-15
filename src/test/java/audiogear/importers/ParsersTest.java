package audiogear.importers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParsersTest {

    @Test
    void parsesAutoEqCsv() throws Exception {
        var p = CurveFileParser.parse("frequency,raw\n20.00,-6.91\n20.20,-6.87\n20000,-12.5\n");
        assertEquals(3, p.size());
        assertEquals(-6.87, p.value()[1], 1e-12);
        assertNull(p.phaseDeg());
    }

    @Test
    void parsesRewExportWithCommentsAndPhase() throws Exception {
        String rew = """
                * Measurement data measured by REW V5.20
                * Source: Impedance
                * Format: Frequency(Hz) Impedance(ohms) Phase(degrees)
                20.000 310.2 12.5
                1000.0 300.1 -0.4
                10000.0 305.9 -3.1
                """;
        var p = CurveFileParser.parse(rew);
        assertEquals(3, p.size());
        assertArrayEquals(new double[]{12.5, -0.4, -3.1}, p.phaseDeg(), 1e-12);
    }

    @Test
    void parsesSemicolonWithDecimalCommaAndQuotedTsv() throws Exception {
        var p = CurveFileParser.parse("Hz;dB\n20,5;3,25\n1000;0\n");
        assertEquals(20.5, p.freqHz()[0], 1e-12);
        assertEquals(3.25, p.value()[0], 1e-12);

        var t = CurveFileParser.parse("\"freq\"\t\"spl\"\n\"100\"\t\"1.5\"\n\"200\"\t\"2\"\n");
        assertEquals(2, t.size());
        assertEquals(1.5, t.value()[0], 1e-12);
    }

    @Test
    void rejectsBadFiles() {
        assertThrows(CurveFileParser.ParseException.class, () -> CurveFileParser.parse("frequency,raw\n100,1\n"));
        assertThrows(CurveFileParser.ParseException.class, () -> CurveFileParser.parse("100,1\n50,2\n"));
        assertThrows(CurveFileParser.ParseException.class, () -> CurveFileParser.parse("100,1\n200000,2\n"));
        var e = assertThrows(CurveFileParser.ParseException.class, () -> CurveFileParser.parse("100,1\n200,2\nhello,world\n"));
        assertTrue(e.getMessage().contains("Line 3"));
    }

    private final BrandParser brands = BrandParser.standard();

    @Test
    void splitsBrandModelAndLabel() {
        assertEquals(new BrandParser.Name("Sennheiser", "HD 650", ""), brands.parse("Sennheiser HD 650"));
        assertEquals(new BrandParser.Name("Campfire Audio", "Andromeda", "2020"), brands.parse("Campfire Audio Andromeda (2020)"));
        assertEquals(new BrandParser.Name("64 Audio", "U12t", ""), brands.parse("64 Audio U12t"));
        assertEquals(new BrandParser.Name("Final Audio Design", "Piano Forte", ""), brands.parse("Final Audio Design Piano Forte"));
        assertEquals(new BrandParser.Name("Drop x Sennheiser", "HD 6XX", ""), brands.parse("Drop x Sennheiser HD 6XX"));
        assertEquals(new BrandParser.Name("Moondrop", "Blessing 2", "sample 1, foam tips"),
                brands.parse("Moondrop  Blessing 2 (sample 1) (foam tips)"));
        assertEquals(new BrandParser.Name("Unknown", "Blessing2", ""), brands.parse("Blessing2"));
        assertEquals("Meze", brands.parse("Meze 99 Classics").brand());
    }

    @Test
    void mapsAutoEqPaths() {
        var e = AutoEqClient.toEntry("crinacle", "data/in-ear/711/Moondrop Blessing 2.csv");
        assertEquals(new AutoEqClient.Entry("crinacle", "in-ear", "711", "Moondrop Blessing 2",
                "measurements/crinacle/data/in-ear/711/Moondrop Blessing 2.csv"), e);
        assertEquals("crinacle · 711", e.sourceLabel());

        var legacy = AutoEqClient.toEntry("Headphone.com Legacy", "data/over-ear/Sennheiser HD 600.csv");
        assertNull(legacy.rig());
        assertEquals("measurements/Headphone.com Legacy/data/over-ear/Sennheiser HD 600.csv", legacy.path());

        assertNull(AutoEqClient.toEntry("x", "README.md"));
        assertNull(AutoEqClient.toEntry("x", "data/speakers/thing.csv"));
        assertNull(AutoEqClient.toEntry("x", "data/over-ear"));
    }

    @Test
    void parsesAmpPowerCells() {
        var p = CatalogCsv.parsePowers("SE:32=1000; balanced:300=250.5");
        assertEquals(2, p.size());
        assertEquals(250.5, p.get(1).powerMw(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> CatalogCsv.parsePowers("32 ohm 1W"));
    }

    @Test
    void csvParserHandlesQuotesNewlinesAndBom() throws Exception {
        var rows = CatalogCsv.parse(new java.io.StringReader("﻿a,b\r\n\"x, y\",\"say \"\"hi\"\"\nthere\"\r\nlast,"));
        assertEquals(List.of("a", "b"), rows.get(0));
        assertEquals(List.of("x, y", "say \"hi\"\nthere"), rows.get(1));
        assertEquals(List.of("last", ""), rows.get(2));
    }
}
