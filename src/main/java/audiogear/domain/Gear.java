package audiogear.domain;

public record Gear(Long id, GearType type, String brand, String model, String variant,
                   Integer releaseYear, Double msrp, String msrpCurrency, GearStatus status,
                   String notes, String createdAt, String updatedAt) {

    public Gear {
        variant = variant == null ? "" : variant.trim();
        status = status == null ? GearStatus.NONE : status;
    }

    public static Gear create(GearType type, String brand, String model) {
        return new Gear(null, type, brand, model, "", null, null, null, GearStatus.NONE, null, null, null);
    }

    public String displayName() { return GearNames.display(brand, model, variant); }

    public Gear withId(Long newId) {
        return new Gear(newId, type, brand, model, variant, releaseYear, msrp, msrpCurrency, status, notes, createdAt, updatedAt);
    }

    public Gear withStatus(GearStatus s) {
        return new Gear(id, type, brand, model, variant, releaseYear, msrp, msrpCurrency, s, notes, createdAt, updatedAt);
    }
}
