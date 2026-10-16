package audiogear.db;

import audiogear.domain.Channel;
import audiogear.domain.Curve;
import audiogear.domain.CurveInfo;
import audiogear.domain.CurveKind;
import audiogear.domain.GearNames;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.RowMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class CurveRepository {

    private static final String INFO_SQL = """
            SELECT c.id, c.gear_id, c.kind, c.source, c.label, c.channel, c.point_count, c.phase_deg IS NOT NULL AS has_phase,
                   c.origin, g.brand, g.model, g.variant
            FROM curve c LEFT JOIN gear g ON g.id = c.gear_id
            """;

    private static final RowMapper<CurveInfo> INFO = (rs, ctx) -> {
        long gid = rs.getLong("gear_id");
        Long gearId = rs.wasNull() ? null : gid;
        String name = gearId == null ? null : GearNames.display(rs.getString("brand"), rs.getString("model"), rs.getString("variant"));
        return new CurveInfo(rs.getLong("id"), gearId, CurveKind.valueOf(rs.getString("kind")), rs.getString("source"),
                rs.getString("label"), Channel.valueOf(rs.getString("channel")), rs.getInt("point_count"),
                rs.getBoolean("has_phase"), rs.getString("origin"), name);
    };

    private static final RowMapper<Curve> CURVE = (rs, ctx) -> {
        long gid = rs.getLong("gear_id");
        Long gearId = rs.wasNull() ? null : gid;
        return new Curve(rs.getLong("id"), gearId, CurveKind.valueOf(rs.getString("kind")), rs.getString("source"),
                rs.getString("label"), Channel.valueOf(rs.getString("channel")),
                Blobs.toDoubles(rs.getBytes("freq_hz")), Blobs.toDoubles(rs.getBytes("value")),
                Blobs.toDoubles(rs.getBytes("phase_deg")), rs.getString("origin"), rs.getString("created_at"));
    };

    private final Jdbi jdbi;

    public CurveRepository(Database db) { this.jdbi = db.jdbi(); }

    public List<CurveInfo> forGear(long gearId) {
        return jdbi.withHandle(h -> h.createQuery(INFO_SQL + " WHERE c.gear_id = :g ORDER BY c.kind, c.source, c.label, c.channel")
                .bind("g", gearId).map(INFO).list());
    }

    public List<CurveInfo> byKind(CurveKind kind) {
        return jdbi.withHandle(h -> h.createQuery(INFO_SQL + " WHERE c.kind = :k ORDER BY c.label")
                .bind("k", kind).map(INFO).list());
    }

    /** Where every stored curve came from (e.g. its AutoEq file URL), for skipping files already imported. */
    public Set<String> origins() {
        return jdbi.withHandle(h -> new HashSet<>(h.createQuery("SELECT origin FROM curve WHERE origin IS NOT NULL")
                .mapTo(String.class).list()));
    }

    public Optional<Curve> load(long id) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM curve WHERE id = :id").bind("id", id).map(CURVE).findOne());
    }

    /** Saves the curve, replacing any existing curve with the same gear, kind, source, label and channel. */
    public long save(Curve c) {
        return jdbi.inTransaction(h -> save(h, c));
    }

    long save(Handle h, Curve c) {
        h.createUpdate("""
                DELETE FROM curve WHERE gear_id IS :g AND kind = :k AND source = :s AND label = :l AND channel = :ch""")
                .bind("g", c.gearId()).bind("k", c.kind()).bind("s", c.source()).bind("l", c.label()).bind("ch", c.channel())
                .execute();
        return h.createUpdate("""
                INSERT INTO curve (gear_id, kind, source, label, channel, point_count, freq_hz, value, phase_deg, origin, created_at)
                VALUES (:g, :k, :s, :l, :ch, :n, :f, :v, :p, :o, :t)""")
                .bind("g", c.gearId()).bind("k", c.kind()).bind("s", c.source()).bind("l", c.label())
                .bind("ch", c.channel()).bind("n", c.pointCount()).bind("f", Blobs.toBytes(c.freqHz()))
                .bind("v", Blobs.toBytes(c.value())).bind("p", Blobs.toBytes(c.phaseDeg())).bind("o", c.origin())
                .bind("t", Instant.now().toString())
                .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
    }

    public void delete(long id) {
        jdbi.useHandle(h -> h.execute("DELETE FROM curve WHERE id = ?", id));
    }
}
