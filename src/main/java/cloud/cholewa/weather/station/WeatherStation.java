package cloud.cholewa.weather.station;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.model.StationSensors;
import cloud.cholewa.weather.reading.CurrentWeather;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

//The station the service reads and what the components of its gateway carry. The registry is read at
//the start and every five minutes (StationRefresh); the map of the sensors is read from the gateway
//when the station or its gateway changes, while there is none, once an hour, and at the next refresh
//after a status of a sensor the map does not know (requestSensors) - a re-pairing gives the sensors
//other ids, and the gateway says nothing about it on the broker (no events/rpc). Another station or
//gateway clears the current state: what the last one measured is not a reading of this one.
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
    private final CurrentWeather currentWeather;
    private final Clock clock;

    private final AtomicBoolean sensorsRequested = new AtomicBoolean();
    //what the registry had twice, as last said at WARN - said again only when it changes
    private final AtomicReference<String> duplicates = new AtomicReference<>("");

    //null while there is no station: before the first successful read of the registry, and after a
    //read without one (registryRead tells the two apart, for the log)
    private final AtomicReference<Station> station = new AtomicReference<>();
    private final AtomicBoolean registryRead = new AtomicBoolean();
    private final AtomicReference<Sensors> sensors = new AtomicReference<>();

    public Mono<Void> refresh() {
        return Mono.defer(this::readRegistry)
            .doOnError(e -> log.warn("Refresh of the weather station from the device registry failed, the last one stays: {}",
                e.toString()))
            .onErrorResume(e -> Mono.justOrEmpty(station.get()).map(Optional::of))
            .flatMap(Mono::justOrEmpty)
            .flatMap(this::readSensorsWhenDue);
    }

    public Optional<Station> station() {
        return Optional.ofNullable(station.get());
    }

    //a sensor the map does not know: the gateway is asked again at the next refresh, not at once -
    //every message of such a sensor asks, and the gateway is a plug
    public void requestSensors() {
        sensorsRequested.set(true);
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
            .map(lists -> {
                warnAboutDuplicates(StationMapper.duplicates(lists.getT1(), lists.getT2()));
                return StationMapper.toStation(lists.getT1(), lists.getT2());
            })
            .doOnNext(read -> {
                final Station previous = station.getAndSet(read.orElse(null));
                final boolean first = !registryRead.getAndSet(true);
                if (first || !read.equals(Optional.ofNullable(previous))) {
                    if (previous != null) {
                        currentWeather.clear();
                    }
                    read.ifPresentOrElse(
                        known -> log.info("Weather station {} ({}) read through the gateway {} at {}",
                            known.stationId(), known.stationAddress(), known.gatewayDeviceId(), known.gatewayAddress()),
                        () -> log.warn("The device registry has no switched-on weather station with a BTHome gateway"));
                }
            });
    }

    private void warnAboutDuplicates(final String found) {
        if (!found.equals(duplicates.getAndSet(found)) && !found.isEmpty()) {
            log.warn("The device registry has more than one switched-on device of a kind, the one with the lowest id is used: {}", found);
        }
    }

    private Mono<Void> readSensorsWhenDue(final Station known) {
        final Sensors read = sensors.get();
        final boolean due = sensorsRequested.getAndSet(false)
            || read == null
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
            .doOnError(e -> {
                sensorsRequested.set(true);
                log.warn("Read of the sensors of the weather station from the gateway {} failed, the last map stays: {}",
                    known.gatewayDeviceId(), e.toString());
            })
            .onErrorComplete()
            .then();
    }
}
