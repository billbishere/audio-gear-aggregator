package audiogear.domain;

/** A measured or target curve. value is dB for FR/TARGET and ohms for IMPEDANCE; phaseDeg may be null. */
public record Curve(Long id, Long gearId, CurveKind kind, String source, String label, Channel channel,
                    double[] freqHz, double[] value, double[] phaseDeg, String origin, String createdAt) {

    public Curve {
        if (freqHz.length != value.length) throw new IllegalArgumentException("freq/value length mismatch");
        if (phaseDeg != null && phaseDeg.length != freqHz.length) throw new IllegalArgumentException("phase length mismatch");
        channel = channel == null ? Channel.AVG : channel;
    }

    public int pointCount() { return freqHz.length; }

    public boolean hasPhase() { return phaseDeg != null; }
}
