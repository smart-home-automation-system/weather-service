package cloud.cholewa.weather.model;

import java.util.Map;

//One read of the gateway: the map of the sensors and the reading each sensor of the station holds
//now - the gateway publishes a sensor only when its value changes, so a value that does not change
//(the battery, the rain for days) reaches the service only this way
public record SensorsRead(StationSensors sensors, Map<Metric, Reading> readings) {

    public SensorsRead {
        readings = Map.copyOf(readings);
    }
}
