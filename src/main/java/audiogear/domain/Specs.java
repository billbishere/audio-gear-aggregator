package audiogear.domain;

/** Factory helpers for empty spec records, so callers don't repeat long null argument lists. */
public final class Specs {
    private Specs() {}

    public static TransducerSpec emptyTransducer(Long gearId) {
        return new TransducerSpec(gearId, null, null, null, null, null, null, null, null, null, null);
    }

    public static AmpSpec emptyAmp(Long gearId) {
        return new AmpSpec(gearId, null, null, null, null, null, null);
    }

    public static DacSpec emptyDac(Long gearId) {
        return new DacSpec(gearId, null, null, null, null, null, null, null);
    }
}
