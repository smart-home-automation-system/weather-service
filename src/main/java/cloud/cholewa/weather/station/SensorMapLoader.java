package cloud.cholewa.weather.station;

import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BthomeDeviceConfig;
import cloud.cholewa.shelly.model.BthomeSensorConfig;
import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.shelly.model.ShellyComponent;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.SensorsRead;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.model.StationSensors;
import cloud.cholewa.weather.reading.SensorReadings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//Which bthomesensor component of the gateway is which metric of the station, read from the gateway:
//Shelly.GetComponents lists the dynamic components with their config (Shelly.GetStatus leaves them
//out), a few pages of one call each. The config of a bthomesensor says the address of the device it
//belongs to and the object it carries; the bthomedevice with the address of the station is the one
///whose signal and battery are read. The status listed with each sensor of the station is its reading
//now, the only way a value that never changes reaches the service. A sensor of another device on the same gateway is kept apart, to
//be ignored without a word; one of the station with an object the service has no metric for is said
//at WARN. A failed read fails the whole map - half a map would make the rest of the metrics unknown
@Slf4j
@Component
public class SensorMapLoader {

    private static final String SENSOR = "bthomesensor:";
    private static final String DEVICE = "bthomedevice:";

    private final ShellyRpcClient shellyRpcClient;
    //a firmware adds fields to a config, and the models have only what is read here
    private final JsonMapper jsonMapper;

    public SensorMapLoader(final ShellyRpcClient shellyRpcClient, final JsonMapper jsonMapper) {
        this.shellyRpcClient = shellyRpcClient;
        this.jsonMapper = jsonMapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public Mono<SensorsRead> load(final Station station) {
        return shellyRpcClient.dynamicComponents(station.gatewayAddress())
            .map(components -> {
                final Map<Integer, Metric> sensors = sensors(components, station);
                return new SensorsRead(
                    new StationSensors(sensors, others(components, sensors), deviceComponent(components, station)),
                    readings(components, sensors));
            });
    }

    //what each sensor of the station holds now, from the status listed with it
    private Map<Metric, Reading> readings(final List<ShellyComponent> components, final Map<Integer, Metric> sensors) {
        final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
        components.stream()
            .filter(component -> component.getKey() != null && component.getKey().startsWith(SENSOR) && component.getStatus() != null)
            .map(component -> jsonMapper.convertValue(component.getStatus(), BthomeSensorStatus.class))
            .filter(status -> status.getId() != null && sensors.containsKey(status.getId()))
            .forEach(status -> SensorReadings.of(sensors.get(status.getId()), status)
                .ifPresent(reading -> readings.put(sensors.get(status.getId()), reading)));
        return readings;
    }

    //every other sensor the gateway has: those of the other Bluetooth devices it hears - there may be
    //many, and none is an error of the station - and one of the station without a metric, said at
    //WARN once, here
    private Set<Integer> others(final List<ShellyComponent> components, final Map<Integer, Metric> sensors) {
        return sensorConfigs(components)
            .map(BthomeSensorConfig::getId)
            .filter(id -> !sensors.containsKey(id))
            .collect(Collectors.toSet());
    }

    private Stream<BthomeSensorConfig> sensorConfigs(final List<ShellyComponent> components) {
        return components.stream()
            .filter(component -> component.getKey() != null && component.getKey().startsWith(SENSOR) && component.getConfig() != null)
            .map(component -> jsonMapper.convertValue(component.getConfig(), BthomeSensorConfig.class))
            .filter(config -> config.getId() != null);
    }

    private Map<Integer, Metric> sensors(final List<ShellyComponent> components, final Station station) {
        final Map<Integer, Metric> sensors = new HashMap<>();
        sensorConfigs(components)
            .filter(config -> station.stationAddress().equalsIgnoreCase(config.getAddr()))
            .forEach(config -> {
                final Optional<Metric> metric = Metric.of(config.getObjId(), config.getIdx());
                if (metric.isPresent()) {
                    sensors.put(config.getId(), metric.get());
                } else {
                    log.warn("Sensor bthomesensor:{} of the station carries the object {} (index {}), which is no metric of the service",
                        config.getId(), config.getObjId(), config.getIdx());
                }
            });
        return sensors;
    }

    private Integer deviceComponent(final List<ShellyComponent> components, final Station station) {
        return components.stream()
            .filter(component -> component.getKey() != null && component.getKey().startsWith(DEVICE) && component.getConfig() != null)
            .map(component -> jsonMapper.convertValue(component.getConfig(), BthomeDeviceConfig.class))
            .filter(config -> config.getId() != null && station.stationAddress().equalsIgnoreCase(config.getAddr()))
            .map(BthomeDeviceConfig::getId)
            .findFirst()
            .orElse(null);
    }
}
