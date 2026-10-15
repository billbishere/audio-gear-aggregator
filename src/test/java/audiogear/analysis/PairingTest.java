package audiogear.analysis;

import audiogear.domain.AmpOutput;
import audiogear.domain.AmpPower;
import audiogear.domain.SensitivityUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PairingTest {

    @Test
    void convertsSensitivityUnits() {
        // 300 ohm at 97 dB/mW: 1 V delivers 3.333 mW, +5.229 dB
        assertEquals(102.2288, Sensitivity.toDbPerVolt(97, SensitivityUnit.DB_PER_MW, 300), 1e-4);
        assertEquals(97, Sensitivity.toDbPerMilliwatt(102.2288, SensitivityUnit.DB_PER_V, 300), 1e-4);
        assertEquals(110, Sensitivity.toDbPerVolt(110, SensitivityUnit.DB_PER_V, 32), 1e-12);
    }

    @Test
    void requiredDriveForHd600At110dB() {
        var r = Pairing.evaluate(new Pairing.Input(300, 97, SensitivityUnit.DB_PER_MW, 110,
                null, null, null, List.of(), AmpOutput.SE, 0));
        assertEquals(19.953, r.requiredMw(), 1e-3);   // 10^((110-97)/10)
        assertEquals(2.4466, r.requiredVrms(), 1e-4); // sqrt(0.019953 * 300)
        assertNull(r.availableMw());
        assertEquals(Pairing.Verdict.UNKNOWN, r.verdict());
        assertNull(r.impedanceRatio());
        assertTrue(r.meetsEighthRule());
    }

    @Test
    void bothLimitsUseTheSmallerAndTheOutputImpedanceDivider() {
        // 5 V open circuit, 10 ohm output impedance, 300 ohm load: V_load = 5*300/310 = 4.8387 V -> 78.04 mW
        // current limit 200 mA -> 12 W, so voltage wins
        var r = Pairing.evaluate(new Pairing.Input(300, 97, SensitivityUnit.DB_PER_MW, 110,
                10.0, 5.0, 200.0, List.of(), AmpOutput.SE, 0));
        assertEquals(78.044, r.availableMw(), 1e-3);
        assertEquals(Pairing.PowerBasis.VOLTAGE_AND_CURRENT_LIMITS, r.basis());
        assertEquals(10 * Math.log10(78.044 / 19.953), r.headroomDb(), 1e-3);
        assertEquals(Pairing.Verdict.MARGINAL, r.verdict()); // 5.92 dB, just under the 6 dB threshold
        assertEquals(30, r.impedanceRatio(), 1e-9);

        var stronger = Pairing.evaluate(new Pairing.Input(300, 97, SensitivityUnit.DB_PER_MW, 110,
                10.0, 8.0, 200.0, List.of(), AmpOutput.SE, 0));
        assertEquals(Pairing.Verdict.COMFORTABLE, stronger.verdict()); // 8 V -> 199.8 mW, +10 dB
    }

    @Test
    void ratedPowerIsInterpolatedInLogLogAndExtrapolatedPhysically() {
        List<AmpPower> rated = List.of(
                new AmpPower(null, 1L, AmpOutput.SE, 300, 100),
                new AmpPower(null, 1L, AmpOutput.SE, 32, 1000),
                new AmpPower(null, 1L, AmpOutput.BALANCED, 32, 4000));
        List<AmpPower> se = rated.stream().filter(p -> p.output() == AmpOutput.SE)
                .sorted(java.util.Comparator.comparingDouble(AmpPower::loadOhm)).toList();
        double t = (Math.log(100) - Math.log(32)) / (Math.log(300) - Math.log(32));
        double expected = Math.exp(Math.log(1000) + t * (Math.log(100) - Math.log(1000)));
        assertEquals(expected, Pairing.interpolateRated(se, 100), 1e-9);
        assertEquals(50, Pairing.interpolateRated(se, 600), 1e-9);  // constant voltage above 300 ohm
        assertEquals(500, Pairing.interpolateRated(se, 16), 1e-9);  // constant current below 32 ohm

        var r = Pairing.evaluate(new Pairing.Input(32, 100, SensitivityUnit.DB_PER_MW, 110,
                1.0, null, null, rated, AmpOutput.BALANCED, 0));
        assertEquals(4000, r.availableMw(), 1e-9);
        assertEquals(Pairing.PowerBasis.RATED_POWER, r.basis());
    }

    @Test
    void verdictsAndEighthRuleWithCableResistance() {
        // needs 10 mW; amp gives 12 mW -> +0.79 dB -> marginal
        var r = Pairing.evaluate(new Pairing.Input(32, 100, SensitivityUnit.DB_PER_MW, 110,
                3.0, null, null, List.of(new AmpPower(null, 1L, AmpOutput.SE, 32, 12)), AmpOutput.SE, 1.5));
        assertEquals(10, r.requiredMw(), 1e-9);
        assertEquals(Pairing.Verdict.MARGINAL, r.verdict());
        assertEquals(4.5, r.sourceImpedanceOhm(), 1e-12);
        assertFalse(r.meetsEighthRule()); // 32/8 = 4 ohm < 4.5 ohm

        var weak = Pairing.evaluate(new Pairing.Input(32, 100, SensitivityUnit.DB_PER_MW, 110,
                0.5, null, null, List.of(new AmpPower(null, 1L, AmpOutput.SE, 32, 5)), AmpOutput.SE, 0));
        assertEquals(Pairing.Verdict.NOT_ENOUGH, weak.verdict());
        assertTrue(weak.meetsEighthRule());
    }
}
