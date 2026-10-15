package audiogear.db;

import audiogear.domain.PriceEntry;
import audiogear.domain.WishlistItem;
import org.jdbi.v3.core.Jdbi;

import java.util.List;
import java.util.Optional;

public final class WishlistRepository {

    private final Jdbi jdbi;

    public WishlistRepository(Database db) { this.jdbi = db.jdbi(); }

    /** Gear whose status is WISHLIST, highest priority (1) first. */
    public List<WishlistRow> list() {
        return jdbi.withHandle(h -> h.createQuery("WITH " + GearRepository.BEST_PRICE_CTE + """
                SELECT g.id AS gear_id, g.type, g.brand, g.model, g.variant,
                       coalesce(w.priority, 3) AS priority, w.target_price, w.currency, w.notes,
                       b.price AS best_price, b.currency AS best_currency, b.retailer AS best_retailer,
                       b.seen_at AS best_seen_at, g.msrp
                FROM gear g
                LEFT JOIN wishlist_item w ON w.gear_id = g.id
                LEFT JOIN best b ON b.gear_id = g.id AND b.rn = 1
                WHERE g.status = 'WISHLIST'
                ORDER BY priority, g.brand, g.model
                """).mapTo(WishlistRow.class).list());
    }

    public Optional<WishlistItem> item(long gearId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM wishlist_item WHERE gear_id = :g")
                .bind("g", gearId).mapTo(WishlistItem.class).findOne());
    }

    public void saveItem(WishlistItem item) {
        jdbi.useHandle(h -> h.createUpdate("""
                INSERT INTO wishlist_item (gear_id, priority, target_price, currency, notes)
                VALUES (:gearId, :priority, :targetPrice, :currency, :notes)
                ON CONFLICT (gear_id) DO UPDATE SET priority = excluded.priority, target_price = excluded.target_price,
                    currency = excluded.currency, notes = excluded.notes""").bindMethods(item).execute());
    }

    public List<PriceEntry> prices(long gearId) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM price_entry WHERE gear_id = :g ORDER BY seen_at DESC, id DESC")
                .bind("g", gearId).mapTo(PriceEntry.class).list());
    }

    public long addPrice(PriceEntry p) {
        return jdbi.withHandle(h -> h.createUpdate("""
                INSERT INTO price_entry (gear_id, retailer, price, currency, condition, url, seen_at)
                VALUES (:gearId, :retailer, :price, :currency, :condition, :url, :seenAt)""")
                .bindMethods(p).executeAndReturnGeneratedKeys("id").mapTo(Long.class).one());
    }

    public void deletePrice(long id) {
        jdbi.useHandle(h -> h.execute("DELETE FROM price_entry WHERE id = ?", id));
    }
}
