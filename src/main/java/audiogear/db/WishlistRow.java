package audiogear.db;

import audiogear.domain.GearType;

/** One wishlist line with its best current price (lowest of each retailer's latest entry). */
public record WishlistRow(long gearId, GearType type, String brand, String model, String variant,
                          int priority, Double targetPrice, String currency, String notes,
                          Double bestPrice, String bestCurrency, String bestRetailer, String bestSeenAt, Double msrp) {

    public String displayName() { return audiogear.domain.GearNames.display(brand, model, variant); }

    /** best - target; negative means below target. Null when either is unknown. */
    public Double gapToTarget() {
        return bestPrice == null || targetPrice == null ? null : bestPrice - targetPrice;
    }

    public boolean belowTarget() {
        Double gap = gapToTarget();
        return gap != null && gap <= 0;
    }
}
