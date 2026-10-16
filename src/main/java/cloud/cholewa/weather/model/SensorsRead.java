package cloud.cholewa.weather.model;

import cloud.cholewa.shelly.model.BthomeDeviceStatus;

import java.util.Map;

//One read of the gateway: the map of the sensors, the reading each sensor of the station holds now
//and the status of the bthomedevice of the station (null when there is none) - the gateway publishes
//a component only when it changes, so a value that does not change (the battery, the rain for days)
//reaches the service only this way
public record SensorsRead(StationSensors sensors, Map<Metric, Reading> readings, BthomeDeviceStatus device) {

    public SensorsRead {
        readings = Map.copyOf(readings);
    }
}
