package audiogear.domain;

public record WishlistItem(long gearId, int priority, Double targetPrice, String currency, String notes) {}
