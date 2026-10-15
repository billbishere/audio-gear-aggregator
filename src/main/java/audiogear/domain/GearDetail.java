package audiogear.domain;

import java.util.List;

/** Everything editable about one gear item. Spec parts are null when they don't apply to the type. */
public record GearDetail(Gear gear, TransducerSpec transducer, AmpSpec amp, List<AmpPower> powers, DacSpec dac,
                         List<String> tags) {

    public GearDetail {
        powers = powers == null ? List.of() : List.copyOf(powers);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** A new detail with empty specs matching the gear's type. */
    public static GearDetail of(Gear gear) {
        GearType t = gear.type();
        return new GearDetail(gear,
                t.isTransducer() ? Specs.emptyTransducer(gear.id()) : null,
                t.isAmp() ? Specs.emptyAmp(gear.id()) : null,
                List.of(),
                t.isDac() ? Specs.emptyDac(gear.id()) : null,
                List.of());
    }
}
