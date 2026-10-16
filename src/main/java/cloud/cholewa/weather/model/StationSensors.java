package cloud.cholewa.weather.model;

import java.util.Map;
import java.util.Set;

//What the components of the gateway carry for the station: the id of each bthomesensor component
//and its metric, the ids of the bthomesensor components of the other Bluetooth devices on the same
//gateway (known, not the station's - ignored without a word), and the id of the bthomedevice
//component with the address of the station (null when the gateway has none)
public record StationSensors(Map<Integer, Metric> sensors, Set<Integer> others, Integer deviceComponent) {

    public StationSensors {
        sensors = Map.copyOf(sensors);
        others = Set.copyOf(others);
    }
}
