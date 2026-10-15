package audiogear.domain;

public record DacSpec(Long gearId, String chip, Double sinadDb, Double outputVoltageVrms, Double maxSampleRateKhz,
                      Boolean dsd, String inputs, String outputs) {

    public DacSpec withGearId(Long id) {
        return new DacSpec(id, chip, sinadDb, outputVoltageVrms, maxSampleRateKhz, dsd, inputs, outputs);
    }
}
