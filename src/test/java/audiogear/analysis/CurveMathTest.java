package audiogear.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CurveMathTest {

    @Test
    void standardGridSpans20HzTo20kHzAt48PointsPerOctave() {
        double[] g = CurveMath.STANDARD_GRID;
        assertEquals(20, g[0], 1e-9);
        assertEquals(20000, g[g.length - 1], 1e-9);
        assertEquals(Math.pow(2, 1.0 / 48), g[1] / g[0], 1e-12);
        assertEquals(480, g.length); // ceil(9.9658 octaves * 48) = 479 steps + 1
    }

    @Test
    void interpolatesLinearlyInLogFrequency() {
        double[] xs = {100, 1000};
        double[] ys = {0, 10};
        // 316.23 Hz is halfway between 100 and 1000 on a log axis
        assertEquals(5, CurveMath.valueAt(xs, ys, Math.sqrt(100 * 1000)), 1e-9);
        assertEquals(10, CurveMath.valueAt(xs, ys, 1000), 1e-12);
    }

    @Test
    void doesNotExtrapolate() {
        double[] r = CurveMath.resample(new double[]{100, 1000}, new double[]{1, 2}, new double[]{50, 100, 2000});
        assertTrue(Double.isNaN(r[0]));
        assertEquals(1, r[1], 1e-12);
        assertTrue(Double.isNaN(r[2]));
    }

    @Test
    void normalizeMakesReferenceZero() {
        double[] grid = {100, 1000, 10000};
        double[] n = CurveMath.normalize(grid, new double[]{3, 7, 1}, 1000);
        assertArrayEquals(new double[]{-4, 0, -6}, n, 1e-12);
    }

    @Test
    void normalizeSkipsMissingPoints() {
        double[] grid = {100, 1000, 10000};
        double[] n = CurveMath.normalize(grid, new double[]{Double.NaN, 7, 1}, 1000);
        assertTrue(Double.isNaN(n[0]));
        assertEquals(0, n[1], 1e-12);
    }

    @Test
    void smoothingLeavesFlatCurveFlatAndAveragesSpikes() {
        double[] grid = CurveMath.logGrid(100, 1600, 12); // 4 octaves, 1/12-octave steps
        double[] flat = new double[grid.length];
        java.util.Arrays.fill(flat, 2.5);
        assertArrayEquals(flat, CurveMath.smooth(grid, flat, 3), 1e-12);

        double[] spike = new double[grid.length];
        int mid = grid.length / 2;
        spike[mid] = 10;
        double[] s = CurveMath.smooth(grid, spike, 3); // 1/3-octave window = +-2 steps -> 5 points
        assertEquals(2, s[mid], 1e-9);
        assertEquals(2, s[mid + 2], 1e-9);
        assertEquals(0, s[mid + 3], 1e-9);
    }

    @Test
    void differenceAndMaxAbs() {
        double[] d = CurveMath.difference(new double[]{1, 5, Double.NaN}, new double[]{2, 1, 0});
        assertEquals(-1, d[0], 1e-12);
        assertEquals(4, d[1], 1e-12);
        assertTrue(Double.isNaN(d[2]));
        assertEquals(4, CurveMath.maxAbs(d), 1e-12);
    }
}
