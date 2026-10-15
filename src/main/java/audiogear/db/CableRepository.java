package audiogear.db;

import audiogear.domain.Cable;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

public final class CableRepository {

    private final Jdbi jdbi;

    public CableRepository(Database db) { this.jdbi = db.jdbi(); }

    public List<Cable> list() {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM cable ORDER BY name").mapTo(Cable.class).list());
    }

    public long save(Cable c) {
        return jdbi.withHandle(h -> {
            if (c.id() == null) {
                return h.createUpdate("""
                        INSERT INTO cable (gear_id, name, source_plug, device_plug, length_m, conductor, loop_resistance_ohm, owned)
                        VALUES (:gearId, :name, :sourcePlug, :devicePlug, :lengthM, :conductor, :loopResistanceOhm, :owned)""")
                        .bindMethods(c).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
            }
            h.createUpdate("""
                    UPDATE cable SET gear_id = :gearId, name = :name, source_plug = :sourcePlug, device_plug = :devicePlug,
                           length_m = :lengthM, conductor = :conductor, loop_resistance_ohm = :loopResistanceOhm, owned = :owned
                    WHERE id = :id""").bindMethods(c).execute();
            return c.id();
        });
    }

    public void delete(long id) {
        jdbi.useHandle(h -> h.execute("DELETE FROM cable WHERE id = ?", id));
    }
}
