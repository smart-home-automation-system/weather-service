package cloud.cholewa.weather.model;

import java.util.Arrays;
import java.util.Optional;

//What the WS90 measures, by the BTHome object it sends it as (the object id, and the index for an
//object it sends twice - the wind speed and the gust are both 0x44). The gateway names a sensor
//component by its own id (bthomesensor:2xx); which object a component carries comes from its
//config, so the ids may change with a re-pairing and nothing here depends on them.
//RAIN is the only flag, every other metric a number.
//The api name is what the read API calls a metric (the keys of /current, the metric of /history) -
//wire contract of the dashboard and the blinds schedules, pinned by MetricTest; the column is the one
//of weather_reading (V1) the history reads
public enum Metric {
    BATTERY(0x01, 0, "battery", "battery"),
    PRESSURE(0x04, 0, "pressure", "pressure"),
    ILLUMINANCE(0x05, 0, "illuminance", "illuminance"),
    DEW_POINT(0x08, 0, "dewPoint", "dew_point"),
    VOLTAGE(0x0C, 0, "voltage", "voltage"),
    RAIN(0x20, 0, "raining", "raining"),
    HUMIDITY(0x2E, 0, "humidity", "humidity"),
    WIND(0x44, 0, "wind", "wind"),
    GUST(0x44, 1, "gust", "gust"),
    TEMPERATURE(0x45, 0, "temperature", "temperature"),
    UV(0x46, 0, "uv", "uv"),
    WIND_DIRECTION(0x5E, 0, "windDirection", "wind_direction"),
    PRECIPITATION(0x5F, 0, "precipitation", "precipitation");

    private final int objectId;
    private final int index;
    private final String apiName;
    private final String column;

    Metric(final int objectId, final int index, final String apiName, final String column) {
        this.objectId = objectId;
        this.index = index;
        this.apiName = apiName;
        this.column = column;
    }

    public String column() {
        return column;
    }

    public String apiName() {
        return apiName;
    }

    //the metric of a name of the read API, exactly as written - no other case, no constant name
    public static Optional<Metric> ofApiName(final String name) {
        return Arrays.stream(values()).filter(metric -> metric.apiName.equals(name)).findFirst();
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

    //an angle: 350 and 10 are 20 degrees apart, not 340 - no arithmetic mean, no minimum or maximum
    public boolean isAngle() {
        return this == WIND_DIRECTION;
    }
}
