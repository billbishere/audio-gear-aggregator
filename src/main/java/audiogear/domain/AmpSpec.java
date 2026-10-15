package audiogear.domain;

public record AmpSpec(Long gearId, Double outputImpedanceOhm, Double maxVoltageVrms, Double maxCurrentMa,
                      String gainSettings, String topology, String outputs) {

    public AmpSpec withGearId(Long id) {
        return new AmpSpec(id, outputImpedanceOhm, maxVoltageVrms, maxCurrentMa, gainSettings, topology, outputs);
    }
}
