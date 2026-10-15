package audiogear.importers;

import audiogear.db.GearRepository;
import audiogear.domain.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catalog import/export as CSV, one row per gear item. On import, rows are matched to existing gear by
 * brand + model + variant; empty cells leave the stored value unchanged.
 * amp_power cells look like "SE:32=1000; BALANCED:32=4000" (output:load ohms=milliwatts).
 */
public final class CatalogCsv {
    private CatalogCsv() {}

    public static final List<String> COLUMNS = List.of(
            "type", "brand", "model", "variant", "status", "release_year", "msrp", "msrp_currency",
            "form", "back", "driver_type", "driver_config", "driver_size_mm", "impedance_ohm", "sensitivity",
            "sensitivity_unit", "weight_g", "connector",
            "output_impedance_ohm", "max_voltage_vrms", "max_current_ma", "gain_settings", "topology", "amp_outputs", "amp_power",
            "chip", "sinad_db", "output_voltage_vrms", "max_sample_rate_khz", "dsd", "dac_inputs", "dac_outputs",
            "tags", "notes");

    private static final Pattern POWER = Pattern.compile("(SE|BALANCED)\\s*:\\s*([0-9.]+)\\s*=\\s*([0-9.]+)", Pattern.CASE_INSENSITIVE);

    public static void write(List<GearDetail> items, Writer w) throws IOException {
        writeRow(w, COLUMNS);
        for (GearDetail d : items) {
            Map<String, String> row = toRow(d);
            List<String> cells = new ArrayList<>();
            for (String c : COLUMNS) cells.add(row.getOrDefault(c, ""));
            writeRow(w, cells);
        }
        w.flush();
    }

    public static ImportSummary read(Reader r, GearRepository repo) throws IOException {
        List<List<String>> rows = parse(r);
        if (rows.isEmpty()) return new ImportSummary(0, 0, List.of("The file is empty"), false);
        List<String> header = rows.getFirst().stream().map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        int imported = 0, skipped = 0;
        List<String> problems = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
            if (cells.stream().allMatch(String::isBlank)) continue;
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size() && c < cells.size(); c++) row.put(header.get(c), cells.get(c).strip());
            try {
                String brand = req(row, "brand"), model = req(row, "model");
                GearDetail base = repo.findId(brand, model, row.getOrDefault("variant", "")).flatMap(repo::get).orElse(null);
                repo.save(apply(base, row));
                imported++;
            } catch (RuntimeException e) {
                skipped++;
                problems.add("Row " + (i + 1) + ": " + e.getMessage());
            }
        }
        return new ImportSummary(imported, skipped, problems, false);
    }

    static Map<String, String> toRow(GearDetail d) {
        Map<String, String> m = new LinkedHashMap<>();
        Gear g = d.gear();
        m.put("type", g.type().name());
        m.put("brand", g.brand());
        m.put("model", g.model());
        m.put("variant", g.variant());
        m.put("status", g.status().name());
        m.put("release_year", str(g.releaseYear()));
        m.put("msrp", str(g.msrp()));
        m.put("msrp_currency", str(g.msrpCurrency()));
        if (d.transducer() != null) {
            TransducerSpec t = d.transducer();
            m.put("form", str(t.form()));
            m.put("back", str(t.back()));
            m.put("driver_type", str(t.driverType()));
            m.put("driver_config", str(t.driverConfig()));
            m.put("driver_size_mm", str(t.driverSizeMm()));
            m.put("impedance_ohm", str(t.impedanceOhm()));
            m.put("sensitivity", str(t.sensitivity()));
            m.put("sensitivity_unit", str(t.sensitivityUnit()));
            m.put("weight_g", str(t.weightG()));
            m.put("connector", str(t.connector()));
        }
        if (d.amp() != null) {
            AmpSpec a = d.amp();
            m.put("output_impedance_ohm", str(a.outputImpedanceOhm()));
            m.put("max_voltage_vrms", str(a.maxVoltageVrms()));
            m.put("max_current_ma", str(a.maxCurrentMa()));
            m.put("gain_settings", str(a.gainSettings()));
            m.put("topology", str(a.topology()));
            m.put("amp_outputs", str(a.outputs()));
            List<String> p = new ArrayList<>();
            for (AmpPower ap : d.powers()) p.add(ap.output().name() + ":" + num(ap.loadOhm()) + "=" + num(ap.powerMw()));
            m.put("amp_power", String.join("; ", p));
        }
        if (d.dac() != null) {
            DacSpec x = d.dac();
            m.put("chip", str(x.chip()));
            m.put("sinad_db", str(x.sinadDb()));
            m.put("output_voltage_vrms", str(x.outputVoltageVrms()));
            m.put("max_sample_rate_khz", str(x.maxSampleRateKhz()));
            m.put("dsd", x.dsd() == null ? "" : x.dsd().toString());
            m.put("dac_inputs", str(x.inputs()));
            m.put("dac_outputs", str(x.outputs()));
        }
        m.put("tags", String.join(", ", d.tags()));
        m.put("notes", str(g.notes()));
        return m;
    }

    /** Applies non-empty cells on top of base (or a new item when base is null). */
    static GearDetail apply(GearDetail base, Map<String, String> row) {
        GearType type = enumOr(GearType.class, row, "type", base == null ? null : base.gear().type());
        if (type == null) throw new IllegalArgumentException("type is required");
        Gear b = base == null ? Gear.create(type, req(row, "brand"), req(row, "model")) : base.gear();
        Gear g = new Gear(b.id(), type, textOr(row, "brand", b.brand()), textOr(row, "model", b.model()),
                textOr(row, "variant", b.variant()), intOr(row, "release_year", b.releaseYear()), dblOr(row, "msrp", b.msrp()),
                textOr(row, "msrp_currency", b.msrpCurrency()), enumOr(GearStatus.class, row, "status", b.status()),
                textOr(row, "notes", b.notes()), b.createdAt(), b.updatedAt());
        GearDetail empty = GearDetail.of(g);
        GearDetail old = base == null ? empty : base;

        TransducerSpec t = null;
        if (type.isTransducer()) {
            TransducerSpec o = old.transducer() != null ? old.transducer() : empty.transducer();
            t = new TransducerSpec(g.id(), enumOr(Form.class, row, "form", o.form()), enumOr(Back.class, row, "back", o.back()),
                    enumOr(DriverType.class, row, "driver_type", o.driverType()), textOr(row, "driver_config", o.driverConfig()),
                    dblOr(row, "driver_size_mm", o.driverSizeMm()), dblOr(row, "impedance_ohm", o.impedanceOhm()),
                    dblOr(row, "sensitivity", o.sensitivity()), enumOr(SensitivityUnit.class, row, "sensitivity_unit", o.sensitivityUnit()),
                    dblOr(row, "weight_g", o.weightG()), textOr(row, "connector", o.connector()));
        }
        AmpSpec a = null;
        List<AmpPower> powers = List.of();
        if (type.isAmp()) {
            AmpSpec o = old.amp() != null ? old.amp() : empty.amp();
            a = new AmpSpec(g.id(), dblOr(row, "output_impedance_ohm", o.outputImpedanceOhm()),
                    dblOr(row, "max_voltage_vrms", o.maxVoltageVrms()), dblOr(row, "max_current_ma", o.maxCurrentMa()),
                    textOr(row, "gain_settings", o.gainSettings()), textOr(row, "topology", o.topology()),
                    textOr(row, "amp_outputs", o.outputs()));
            String p = row.getOrDefault("amp_power", "");
            powers = p.isBlank() ? old.powers() : parsePowers(p);
        }
        DacSpec dac = null;
        if (type.isDac()) {
            DacSpec o = old.dac() != null ? old.dac() : empty.dac();
            String dsd = row.getOrDefault("dsd", "");
            dac = new DacSpec(g.id(), textOr(row, "chip", o.chip()), dblOr(row, "sinad_db", o.sinadDb()),
                    dblOr(row, "output_voltage_vrms", o.outputVoltageVrms()), dblOr(row, "max_sample_rate_khz", o.maxSampleRateKhz()),
                    dsd.isBlank() ? o.dsd() : parseBool(dsd), textOr(row, "dac_inputs", o.inputs()), textOr(row, "dac_outputs", o.outputs()));
        }
        String tags = row.getOrDefault("tags", "");
        List<String> tagList = tags.isBlank() ? old.tags() : List.of(tags.split("\\s*,\\s*"));
        return new GearDetail(g, t, a, powers, dac, tagList);
    }

    static List<AmpPower> parsePowers(String text) {
        List<AmpPower> out = new ArrayList<>();
        for (String part : text.split(";")) {
            if (part.isBlank()) continue;
            Matcher m = POWER.matcher(part.strip());
            if (!m.matches()) throw new IllegalArgumentException("amp_power entry \"" + part.strip() + "\" should look like SE:32=1000");
            out.add(new AmpPower(null, null, AmpOutput.valueOf(m.group(1).toUpperCase(Locale.ROOT)),
                    Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))));
        }
        return out;
    }

    private static boolean parseBool(String s) {
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1" -> true;
            case "false", "no", "n", "0" -> false;
            default -> throw new IllegalArgumentException("dsd must be yes or no, not \"" + s + "\"");
        };
    }

    private static String req(Map<String, String> row, String col) {
        String v = row.get(col);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(col + " is required");
        return v.strip();
    }

    private static String textOr(Map<String, String> row, String col, String def) {
        String v = row.get(col);
        return v == null || v.isBlank() ? def : v.strip();
    }

    private static Double dblOr(Map<String, String> row, String col, Double def) {
        String v = row.get(col);
        if (v == null || v.isBlank()) return def;
        try {
            return Double.parseDouble(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(col + " must be a number, not \"" + v + "\"");
        }
    }

    private static Integer intOr(Map<String, String> row, String col, Integer def) {
        Double d = dblOr(row, col, def == null ? null : def.doubleValue());
        return d == null ? null : (int) Math.round(d);
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, Map<String, String> row, String col, E def) {
        String v = row.get(col);
        if (v == null || v.isBlank()) return def;
        try {
            return Enums.parse(type, v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(col + ": " + e.getMessage());
        }
    }

    private static String str(Object o) {
        if (o == null) return "";
        if (o instanceof Enum<?> e) return e.name();
        if (o instanceof Double d) return num(d);
        return o.toString();
    }

    private static String num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }

    // RFC 4180 CSV

    static void writeRow(Writer w, List<String> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) w.write(',');
            String c = cells.get(i) == null ? "" : cells.get(i);
            if (c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r")) {
                w.write('"');
                w.write(c.replace("\"", "\"\""));
                w.write('"');
            } else {
                w.write(c);
            }
        }
        w.write("\r\n");
    }

    static List<List<String>> parse(Reader in) throws IOException {
        Reader r = in.markSupported() ? in : new java.io.BufferedReader(in);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, any = false;
        int ch;
        while ((ch = r.read()) != -1) {
            char c = (char) ch;
            if (!any && c == '﻿') { any = true; continue; } // byte-order mark
            any = true;
            if (quoted) {
                if (c == '"') {
                    r.mark(1);
                    int next = r.read();
                    if (next == '"') cell.append('"');
                    else { quoted = false; if (next != -1) { r.reset(); } }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    r.mark(1);
                    if (r.read() != '\n') r.reset();
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
