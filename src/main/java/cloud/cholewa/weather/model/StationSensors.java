package cloud.cholewa.weather.model;

import java.util.Map;

//What the components of the gateway carry for the station: the id of each bthomesensor component
//and its metric, and the id of the bthomedevice component with the address of the station (null when
//the gateway has none)
public record StationSensors(Map<Integer, Metric> sensors, Integer deviceComponent) {

    public StationSensors {
        sensors = Map.copyOf(sensors);
    }
}
