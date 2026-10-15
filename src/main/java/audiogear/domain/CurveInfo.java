package audiogear.domain;

/** Curve metadata without the point data. gearName is null for target curves. */
public record CurveInfo(long id, Long gearId, CurveKind kind, String source, String label, Channel channel,
                        int pointCount, boolean hasPhase, String origin, String gearName) {

    /** e.g. "Sennheiser HD 650 · sample 2 [oratory1990]" or "Harman over-ear 2018 [AutoEq]". */
    public String title() {
        boolean named = gearName != null && !gearName.isBlank();
        String head = named ? gearName : label;
        String extra = named && !label.isBlank() && !label.equals(gearName) ? " · " + label : "";
        String chan = channel == Channel.AVG ? "" : " " + channel;
        return head + extra + " [" + source + chan + "]";
    }

    @Override public String toString() { return title(); }
}
