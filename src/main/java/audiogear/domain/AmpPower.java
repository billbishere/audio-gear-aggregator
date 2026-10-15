package audiogear.domain;

/** One rated power figure, e.g. 1000 mW into 32 ohms from the balanced output. */
public record AmpPower(Long id, Long gearId, AmpOutput output, double loadOhm, double powerMw) {}
