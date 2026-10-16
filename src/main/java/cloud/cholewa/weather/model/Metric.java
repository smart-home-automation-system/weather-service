package cloud.cholewa.weather.model;

import java.util.Arrays;
import java.util.Optional;

//What the WS90 measures, by the BTHome object it sends it as (the object id, and the index for an
//object it sends twice - the wind speed and the gust are both 0x44). The gateway names a sensor
//component by its own id (bthomesensor:2xx); which object a component carries comes from its
//config, so the ids may change with a re-pairing and nothing here depends on them.
//RAIN is the only flag, every other metric a number
public enum Metric {
    BATTERY(0x01, 0),
    PRESSURE(0x04, 0),
    ILLUMINANCE(0x05, 0),
    DEW_POINT(0x08, 0),
    VOLTAGE(0x0C, 0),
    RAIN(0x20, 0),
    HUMIDITY(0x2E, 0),
    WIND(0x44, 0),
    GUST(0x44, 1),
    TEMPERATURE(0x45, 0),
    UV(0x46, 0),
    WIND_DIRECTION(0x5E, 0),
    PRECIPITATION(0x5F, 0);

    private final int objectId;
    private final int index;

    Metric(final int objectId, final int index) {
        this.objectId = objectId;
        this.index = index;
    }

    public static Optional<Metric> of(final Integer objectId, final Integer index) {
        if (objectId == null) {
            return Optional.empty();
        }
        final int idx = index == null ? 0 : index;
        return Arrays.stream(values())
            .filter(metric -> metric.objectId == objectId && metric.index == idx)
            .findFirst();
    }

    public boolean isFlag() {
        return this == RAIN;
    }
}
