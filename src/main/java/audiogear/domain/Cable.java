package audiogear.domain;

/** A cable; gearId is set when it is that gear's stock cable. loopResistanceOhm is the round-trip resistance. */
public record Cable(Long id, Long gearId, String name, String sourcePlug, String devicePlug, Double lengthM,
                    String conductor, Double loopResistanceOhm, boolean owned) {

    @Override public String toString() { return name; }
}
