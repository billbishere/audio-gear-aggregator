package audiogear.domain;

/** One catalog row: gear plus the spec fields the table shows and filters on. */
public record GearSummary(long id, GearType type, String brand, String model, String variant, GearStatus status,
                          Double msrp, String msrpCurrency, Integer releaseYear,
                          Form form, Back back, DriverType driverType, String driverConfig,
                          Double impedanceOhm, Double sensitivity, SensitivityUnit sensitivityUnit, Double weightG,
                          Double outputImpedanceOhm, Double sinadDb, String chip,
                          int curveCount, int threadCount, int recentThreadCount, String tags,
                          Double bestPrice, String bestPriceCurrency) {

    public String displayName() { return GearNames.display(brand, model, variant); }

    /** Best current price if known, otherwise MSRP. */
    public Double effectivePrice() { return bestPrice != null ? bestPrice : msrp; }

    @Override public String toString() { return displayName(); }
}
