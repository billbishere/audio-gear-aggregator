package audiogear.domain;

public record PriceEntry(Long id, long gearId, String retailer, double price, String currency,
                         Condition condition, String url, String seenAt) {}
