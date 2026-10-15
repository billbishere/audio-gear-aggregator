package audiogear.domain;

public enum GearType {
    HEADPHONE, IEM, AMP, DAC, AMP_DAC;

    public boolean isTransducer() { return this == HEADPHONE || this == IEM; }
    public boolean isAmp() { return this == AMP || this == AMP_DAC; }
    public boolean isDac() { return this == DAC || this == AMP_DAC; }
}
