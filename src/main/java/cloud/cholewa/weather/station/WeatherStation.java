package cloud.cholewa.weather.station;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.model.StationSensors;
import cloud.cholewa.weather.registry.DeviceRegistryClient;
import cloud.cholewa.weather.registry.StationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

//The station the service reads and what the components of its gateway carry. The registry is read at
//the start and every five minutes (StationRefresh); the map of the sensors is read from the gateway
//when the station or its gateway changes, while there is none, and once an hour - a re-pairing gives
//the sensors other ids, and the gateway says nothing about it on the broker (no events/rpc).
//A failed read keeps what was known: a database-service or a gateway away for a moment must not make
//the station unknown. Until the first read the station is unknown and every event is ignored
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherStation {

    static final Duration SENSORS_MAX_AGE = Duration.ofHours(1);

    private record Sensors(Station station, StationSensors sensors, Instant readAt) {
    }

    private final DeviceRegistryClient deviceRegistryClient;
    private final SensorMapLoader sensorMapLoader;
    private final Clock clock;

    //null until the first successful read of the registry; an empty Optional is "no station"
    private final AtomicReference<Optional<Station>> station = new AtomicReference<>();
    private final AtomicReference<Sensors> sensors = new AtomicReference<>();

    public Mono<Void> refresh() {
        return Mono.defer(this::readRegistry)
            .doOnError(e -> log.warn("Refresh of the weather station from the device registry failed, the last one stays: {}",
                e.toString()))
            .onErrorResume(e -> station.get() == null ? Mono.empty() : Mono.just(station.get()))
            .flatMap(Mono::justOrEmpty)
            .flatMap(this::readSensorsWhenDue);
    }

    public Optional<Station> station() {
        final Optional<Station> known = station.get();
        return known == null ? Optional.empty() : known;
    }

    //the map of the station as it is now - never one read for a station or a gateway since replaced
    public Optional<StationSensors> sensors() {
        final Sensors read = sensors.get();
        return read != null && station().filter(read.station()::equals).isPresent()
            ? Optional.of(read.sensors())
            : Optional.empty();
    }

    private Mono<Optional<Station>> readRegistry() {
        return deviceRegistryClient.getDevices(DeviceKind.WEATHER_STATION)
            .zipWhen(stations -> deviceRegistryClient.getDevices(DeviceKind.BTHOME_GATEWAY))
            .map(lists -> StationMapper.toStation(lists.getT1(), lists.getT2()))
            .doOnNext(read -> {
                final Optional<Station> previous = station.getAndSet(read);
                if (!read.equals(previous)) {
                    read.ifPresentOrElse(
                        known -> log.info("Weather station {} ({}) read through the gateway {} at {}",
                            known.stationId(), known.stationAddress(), known.gatewayDeviceId(), known.gatewayAddress()),
                        () -> log.warn("The device registry has no switched-on weather station with a BTHome gateway"));
                }
            });
    }

    private Mono<Void> readSensorsWhenDue(final Station known) {
        final Sensors read = sensors.get();
        final boolean due = read == null
            || !read.station().equals(known)
            || !read.readAt().plus(SENSORS_MAX_AGE).isAfter(clock.instant());
        if (!due) {
            return Mono.empty();
        }
        return sensorMapLoader.load(known)
            .doOnNext(loaded -> {
                final Sensors previous = sensors.getAndSet(new Sensors(known, loaded, clock.instant()));
                if (previous == null || !previous.sensors().equals(loaded)) {
                    log.info("Sensors of the weather station on the gateway {}: {}", known.gatewayDeviceId(), loaded);
                }
            })
            .doOnError(e -> log.warn("Read of the sensors of the weather station from the gateway {} failed, the last map stays: {}",
                known.gatewayDeviceId(), e.toString()))
            .onErrorComplete()
            .then();
    }
}
