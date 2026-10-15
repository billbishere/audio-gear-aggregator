package audiogear.db;

import audiogear.domain.AmpPower;
import audiogear.domain.AmpSpec;
import audiogear.domain.DacSpec;
import audiogear.domain.Gear;
import audiogear.domain.GearDetail;
import audiogear.domain.GearStatus;
import audiogear.domain.GearSummary;
import audiogear.domain.TransducerSpec;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

public final class GearRepository {

    /** Days a thread counts as "recent" in the catalog. */
    public static final int RECENT_DAYS = 90;

    /**
     * Best current price per gear: each retailer's latest entry, then the cheapest of those.
     * Shared by the catalog and wishlist queries.
     */
    static final String BEST_PRICE_CTE = """
            latest AS (
                SELECT p.gear_id, p.retailer, p.price, p.currency, p.seen_at,
                       ROW_NUMBER() OVER (PARTITION BY p.gear_id, p.retailer ORDER BY p.seen_at DESC, p.id DESC) AS rn
                FROM price_entry p
            ),
            best AS (
                SELECT gear_id, price, currency, retailer, seen_at,
                       ROW_NUMBER() OVER (PARTITION BY gear_id ORDER BY price ASC, seen_at DESC) AS rn
                FROM latest WHERE rn = 1
            )""";

    private static final String SUMMARY_SQL = "WITH " + BEST_PRICE_CTE + """
            SELECT g.id, g.type, g.brand, g.model, g.variant, g.status, g.msrp, g.msrp_currency, g.release_year,
                   t.form, t.back, t.driver_type, t.driver_config, t.impedance_ohm, t.sensitivity, t.sensitivity_unit,
                   t.weight_g, a.output_impedance_ohm, d.sinad_db, d.chip,
                   (SELECT count(*) FROM curve c WHERE c.gear_id = g.id) AS curve_count,
                   (SELECT count(*) FROM thread_gear tg WHERE tg.gear_id = g.id AND tg.link_kind <> 'REJECTED') AS thread_count,
                   (SELECT count(*) FROM thread_gear tg JOIN thread th ON th.id = tg.thread_id
                     WHERE tg.gear_id = g.id AND tg.link_kind <> 'REJECTED'
                       AND coalesce(th.posted_at, th.first_seen_at) >= :recentSince) AS recent_thread_count,
                   (SELECT group_concat(name, ', ') FROM (SELECT tag.name FROM gear_tag gt JOIN tag ON tag.id = gt.tag_id
                     WHERE gt.gear_id = g.id ORDER BY tag.name)) AS tags,
                   b.price AS best_price, b.currency AS best_price_currency
            FROM gear g
            LEFT JOIN transducer_spec t ON t.gear_id = g.id
            LEFT JOIN amp_spec a ON a.gear_id = g.id
            LEFT JOIN dac_spec d ON d.gear_id = g.id
            LEFT JOIN best b ON b.gear_id = g.id AND b.rn = 1
            """;

    /** Minimal identity for name matching. */
    public record GearRef(long id, String brand, String model, String variant) {}

    private final Jdbi jdbi;

    public GearRepository(Database db) { this.jdbi = db.jdbi(); }

    public List<GearSummary> listSummaries() {
        return jdbi.withHandle(h -> h.createQuery(SUMMARY_SQL + " ORDER BY g.brand, g.model, g.variant")
                .bind("recentSince", recentSince()).mapTo(GearSummary.class).list());
    }

    public Optional<GearSummary> summary(long id) {
        return jdbi.withHandle(h -> h.createQuery(SUMMARY_SQL + " WHERE g.id = :id")
                .bind("recentSince", recentSince()).bind("id", id).mapTo(GearSummary.class).findOne());
    }

    public List<GearRef> listRefs() {
        return jdbi.withHandle(h -> h.createQuery("SELECT id, brand, model, variant FROM gear")
                .map((rs, ctx) -> new GearRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4))).list());
    }

    public Optional<Long> findId(String brand, String model, String variant) {
        return jdbi.withHandle(h -> h.createQuery("SELECT id FROM gear WHERE brand = :b AND model = :m AND variant = :v")
                .bind("b", brand).bind("m", model).bind("v", variant == null ? "" : variant.trim())
                .mapTo(Long.class).findOne());
    }

    public Optional<GearDetail> get(long id) {
        return jdbi.withHandle(h -> {
            Optional<Gear> gear = h.createQuery("SELECT * FROM gear WHERE id = :id").bind("id", id).mapTo(Gear.class).findOne();
            if (gear.isEmpty()) return Optional.<GearDetail>empty();
            Gear g = gear.get();
            TransducerSpec t = h.createQuery("SELECT * FROM transducer_spec WHERE gear_id = :id").bind("id", id)
                    .mapTo(TransducerSpec.class).findOne().orElse(null);
            AmpSpec a = h.createQuery("SELECT * FROM amp_spec WHERE gear_id = :id").bind("id", id)
                    .mapTo(AmpSpec.class).findOne().orElse(null);
            List<AmpPower> p = h.createQuery("SELECT * FROM amp_power WHERE gear_id = :id ORDER BY output, load_ohm").bind("id", id)
                    .mapTo(AmpPower.class).list();
            DacSpec d = h.createQuery("SELECT * FROM dac_spec WHERE gear_id = :id").bind("id", id)
                    .mapTo(DacSpec.class).findOne().orElse(null);
            List<String> tags = h.createQuery("SELECT tag.name FROM gear_tag JOIN tag ON tag.id = gear_tag.tag_id WHERE gear_id = :id ORDER BY tag.name")
                    .bind("id", id).mapTo(String.class).list();
            GearDetail empty = GearDetail.of(g);
            return Optional.of(new GearDetail(g,
                    g.type().isTransducer() ? (t != null ? t : empty.transducer()) : null,
                    g.type().isAmp() ? (a != null ? a : empty.amp()) : null,
                    g.type().isAmp() ? p : List.of(),
                    g.type().isDac() ? (d != null ? d : empty.dac()) : null,
                    tags));
        });
    }

    /** Inserts or updates the gear and all its spec parts in one transaction. Returns the gear id. */
    public long save(GearDetail detail) {
        return jdbi.inTransaction(h -> save(h, detail));
    }

    long save(Handle h, GearDetail detail) {
        Gear g = detail.gear();
        String now = Instant.now().toString();
        long id;
        if (g.id() == null) {
            id = h.createUpdate("""
                    INSERT INTO gear (type, brand, model, variant, release_year, msrp, msrp_currency, status, notes, created_at, updated_at)
                    VALUES (:type, :brand, :model, :variant, :releaseYear, :msrp, :msrpCurrency, :status, :notes, :now, :now)""")
                    .bindMethods(g).bind("now", now).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
        } else {
            id = g.id();
            int n = h.createUpdate("""
                    UPDATE gear SET type = :type, brand = :brand, model = :model, variant = :variant, release_year = :releaseYear,
                           msrp = :msrp, msrp_currency = :msrpCurrency, status = :status, notes = :notes, updated_at = :now
                    WHERE id = :id""").bindMethods(g).bind("now", now).execute();
            if (n == 0) throw new IllegalArgumentException("No gear with id " + id);
        }

        h.execute("DELETE FROM transducer_spec WHERE gear_id = ?", id);
        if (g.type().isTransducer() && detail.transducer() != null) {
            h.createUpdate("""
                    INSERT INTO transducer_spec (gear_id, form, back, driver_type, driver_config, driver_size_mm, impedance_ohm,
                                                 sensitivity, sensitivity_unit, weight_g, connector)
                    VALUES (:gearId, :form, :back, :driverType, :driverConfig, :driverSizeMm, :impedanceOhm,
                            :sensitivity, :sensitivityUnit, :weightG, :connector)""")
                    .bindMethods(detail.transducer().withGearId(id)).execute();
        }

        h.execute("DELETE FROM amp_spec WHERE gear_id = ?", id);
        h.execute("DELETE FROM amp_power WHERE gear_id = ?", id);
        if (g.type().isAmp() && detail.amp() != null) {
            h.createUpdate("""
                    INSERT INTO amp_spec (gear_id, output_impedance_ohm, max_voltage_vrms, max_current_ma, gain_settings, topology, outputs)
                    VALUES (:gearId, :outputImpedanceOhm, :maxVoltageVrms, :maxCurrentMa, :gainSettings, :topology, :outputs)""")
                    .bindMethods(detail.amp().withGearId(id)).execute();
            for (AmpPower p : detail.powers()) {
                h.createUpdate("INSERT INTO amp_power (gear_id, output, load_ohm, power_mw) VALUES (:g, :o, :l, :p)")
                        .bind("g", id).bind("o", p.output()).bind("l", p.loadOhm()).bind("p", p.powerMw()).execute();
            }
        }

        h.execute("DELETE FROM dac_spec WHERE gear_id = ?", id);
        if (g.type().isDac() && detail.dac() != null) {
            h.createUpdate("""
                    INSERT INTO dac_spec (gear_id, chip, sinad_db, output_voltage_vrms, max_sample_rate_khz, dsd, inputs, outputs)
                    VALUES (:gearId, :chip, :sinadDb, :outputVoltageVrms, :maxSampleRateKhz, :dsd, :inputs, :outputs)""")
                    .bindMethods(detail.dac().withGearId(id)).execute();
        }

        h.execute("DELETE FROM gear_tag WHERE gear_id = ?", id);
        for (String tag : new LinkedHashSet<>(cleanTags(detail.tags()))) {
            h.execute("INSERT OR IGNORE INTO tag (name) VALUES (?)", tag);
            long tagId = h.createQuery("SELECT id FROM tag WHERE name = :n").bind("n", tag).mapTo(Long.class).one();
            h.execute("INSERT OR IGNORE INTO gear_tag (gear_id, tag_id) VALUES (?, ?)", id, tagId);
        }
        h.execute("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM gear_tag)");

        if (g.status() == GearStatus.WISHLIST) ensureWishlistItem(h, id);
        return id;
    }

    public void setStatus(long id, GearStatus status) {
        jdbi.useTransaction(h -> {
            h.execute("UPDATE gear SET status = ?, updated_at = ? WHERE id = ?", status, Instant.now().toString(), id);
            if (status == GearStatus.WISHLIST) ensureWishlistItem(h, id);
        });
    }

    private static void ensureWishlistItem(Handle h, long id) {
        h.execute("INSERT OR IGNORE INTO wishlist_item (gear_id, priority) VALUES (?, 3)", id);
    }

    public void delete(long id) {
        jdbi.useHandle(h -> {
            h.execute("DELETE FROM gear WHERE id = ?", id);
            h.execute("DELETE FROM tag WHERE id NOT IN (SELECT tag_id FROM gear_tag)");
        });
    }

    public List<String> allTags() {
        return jdbi.withHandle(h -> h.createQuery("SELECT name FROM tag ORDER BY name").mapTo(String.class).list());
    }

    public int count() {
        return jdbi.withHandle(h -> h.createQuery("SELECT count(*) FROM gear").mapTo(Integer.class).one());
    }

    static List<String> cleanTags(List<String> tags) {
        List<String> out = new ArrayList<>();
        for (String t : tags) if (t != null && !t.isBlank()) out.add(t.strip());
        return out;
    }

    static String recentSince() {
        return Instant.now().minus(Duration.ofDays(RECENT_DAYS)).toString();
    }
}
