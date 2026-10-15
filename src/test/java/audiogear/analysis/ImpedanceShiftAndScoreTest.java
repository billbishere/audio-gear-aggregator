package audiogear.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImpedanceShiftAndScoreTest {

    @Test
    void flatImpedanceGivesNoShift() {
        double[] f = {100, 1000, 10000};
        double[] z = {32, 32, 32};
        assertArrayEquals(new double[]{0, 0, 0}, ImpedanceShift.compute(f, z, null, 10), 1e-12);
    }

    @Test
    void resistiveDividerShiftNormalisedAt1kHz() {
        double[] f = {100, 1000};
        double[] z = {8, 32};
        double[] s = ImpedanceShift.compute(f, z, null, 2);
        double expected = 20 * Math.log10(8.0 / 10) - 20 * Math.log10(32.0 / 34);
        assertEquals(expected, s[0], 1e-9); // about -1.412 dB
        assertEquals(0, s[1], 1e-12);
    }

    @Test
    void phaseIsUsedWhenPresent() {
        double[] f = {100, 1000};
        double[] z = {10, 10};
        double[] phase = {90, 0};
        double[] s = ImpedanceShift.compute(f, z, phase, 10);
        // |j10 / (10 + j10)| = 1/sqrt(2) -> -3.01 dB; at 1 kHz 10/20 -> -6.02 dB; relative +3.01 dB
        assertEquals(20 * Math.log10(1 / Math.sqrt(2)) - 20 * Math.log10(0.5), s[0], 1e-9);
    }

    private static final double[] F = CurveMath.logGrid(20, 20000, 24);

    private static double[] curve(java.util.function.DoubleUnaryOperator fn) {
        double[] y = new double[F.length];
        for (int i = 0; i < F.length; i++) y[i] = fn.applyAsDouble(F[i]);
        return y;
    }

    @Test
    void perfectMatchScoresTheConstant() {
        double[] target = curve(f -> Math.sin(Math.log(f)));
        var oe = PreferenceScore.overEar(F, target, F, target);
        assertEquals(114.490443008238, oe.score(), 1e-9);
        var ie = PreferenceScore.inEar(F, target, F, target);
        assertEquals(100.0795, ie.score(), 1e-9);
        assertEquals(0, ie.meanAbs(), 1e-12);
    }

    @Test
    void levelOffsetDoesNotChangeTheScore() {
        double[] target = curve(f -> 0);
        double[] fr = curve(f -> 6 + Math.sin(Math.log(f) * 3));
        double[] shifted = curve(f -> 6 + Math.sin(Math.log(f) * 3) - 6);
        assertEquals(PreferenceScore.overEar(F, fr, F, target).score(),
                PreferenceScore.overEar(F, shifted, F, target).score(), 1e-9);
        assertEquals(PreferenceScore.inEar(F, fr, F, target).score(),
                PreferenceScore.inEar(F, shifted, F, target).score(), 1e-9);
    }

    @Test
    void tiltIsRecoveredAsSlope() {
        double[] target = curve(f -> 0);
        double[] tilted = curve(f -> -1.5 * Math.log(f));
        var s = PreferenceScore.overEar(F, tilted, F, target);
        assertEquals(-1.5, s.slope(), 1e-6);
        assertTrue(s.score() < 114.49);
    }

    @Test
    void returnsNullWhenCoverageIsTooNarrow() {
        double[] f = {900, 1000, 1100};
        double[] y = {0, 0, 0};
        assertNull(PreferenceScore.overEar(f, y, f, y));
    }
}
