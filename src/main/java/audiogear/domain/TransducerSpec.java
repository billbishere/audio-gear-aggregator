package audiogear.domain;

/** Headphone / IEM specs. */
public record TransducerSpec(Long gearId, Form form, Back back, DriverType driverType, String driverConfig,
                             Double driverSizeMm, Double impedanceOhm, Double sensitivity,
                             SensitivityUnit sensitivityUnit, Double weightG, String connector) {

    public TransducerSpec withGearId(Long id) {
        return new TransducerSpec(id, form, back, driverType, driverConfig, driverSizeMm, impedanceOhm,
                sensitivity, sensitivityUnit, weightG, connector);
    }
}
