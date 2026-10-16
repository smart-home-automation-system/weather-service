package cloud.cholewa.weather.station;

import cloud.cholewa.shelly.http.ShellyRpcClient;
import cloud.cholewa.shelly.model.BthomeDeviceConfig;
import cloud.cholewa.shelly.model.BthomeDeviceStatus;
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
import tools.jackson.core.JacksonException;
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
//Shelly.GetComponents lists the dynamic components with their config and status (Shelly.GetStatus
//leaves them out), a few pages of one call each. The config of a bthomesensor says the address of
//the device it belongs to and the object it carries; the bthomedevice with the address of the
//station is the one whose signal and battery are read. The status listed with each sensor of the
//station is what it holds now - the only way a value that never changes reaches the service.
//A sensor of another device on the same gateway is kept apart, to be ignored without a word; one of
//the station with an object the service has no metric for is said at WARN. A component whose config
//or status is not the model is skipped with a WARN - one odd component must not cost the whole map;
//a failed call fails it - half a map would make the rest of the metrics unknown
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
                final Optional<ShellyComponent> device = device(components, station);
                return new SensorsRead(
                    new StationSensors(sensors, others(components, sensors), device.map(SensorMapLoader::id).orElse(null)),
                    readings(components, sensors),
                    device.flatMap(component -> as(component, component.getStatus(), BthomeDeviceStatus.class)).orElse(null));
            });
    }

    //what each sensor of the station holds now. Two sensors of one metric (a re-pairing that left the
    //old component) give the newer reading. A value of the wrong type is said at WARN by the router,
    //when the sensor publishes - not on every read of the map
    private Map<Metric, Reading> readings(final List<ShellyComponent> components, final Map<Integer, Metric> sensors) {
        final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
        ofKind(components, SENSOR)
            .filter(component -> sensors.containsKey(id(component)))
            .forEach(component -> {
                final Metric metric = sensors.get(id(component));
                as(component, component.getStatus(), BthomeSensorStatus.class)
                    .flatMap(status -> SensorReadings.of(metric, status, false))
                    .ifPresent(reading -> readings.merge(metric, reading,
                        (held, other) -> other.measuredAt().isAfter(held.measuredAt()) ? other : held));
            });
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

    private Optional<ShellyComponent> device(final List<ShellyComponent> components, final Station station) {
        return ofKind(components, DEVICE)
            .filter(component -> as(component, component.getConfig(), BthomeDeviceConfig.class)
                .filter(config -> station.stationAddress().equalsIgnoreCase(config.getAddr()))
                .isPresent())
            .findFirst();
    }

    private Stream<BthomeSensorConfig> sensorConfigs(final List<ShellyComponent> components) {
        return ofKind(components, SENSOR)
            .flatMap(component -> as(component, component.getConfig(), BthomeSensorConfig.class)
                .filter(config -> id(component).equals(config.getId()))
                .stream());
    }

    //the components of one type with an id in the key ("bthomesensor:205")
    private static Stream<ShellyComponent> ofKind(final List<ShellyComponent> components, final String prefix) {
        return components.stream()
            .filter(component -> component.getKey() != null && component.getKey().startsWith(prefix))
            .filter(component -> id(component) != null);
    }

    private static Integer id(final ShellyComponent component) {
        final String id = component.getKey().substring(component.getKey().indexOf(':') + 1);
        return !id.isEmpty() && id.length() <= 9 && id.chars().allMatch(Character::isDigit) ? Integer.valueOf(id) : null;
    }

    private <T> Optional<T> as(final ShellyComponent component, final Map<String, Object> raw, final Class<T> model) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jsonMapper.convertValue(raw, model));
        } catch (JacksonException | IllegalArgumentException notTheModel) {
            log.warn("Component {} of the gateway is not a {} and is skipped: {}",
                component.getKey(), model.getSimpleName(), notTheModel.getMessage());
            return Optional.empty();
        }
    }
}
