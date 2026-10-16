package audiogear.feed;

import audiogear.domain.GearType;

/** A model the collector can recognise in thread titles. */
public record KnownGear(GearType type, String brand, String model) {}
