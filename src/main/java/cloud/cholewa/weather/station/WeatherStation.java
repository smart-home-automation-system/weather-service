package cloud.cholewa.weather.station;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.model.StationLink;
import cloud.cholewa.weather.model.StationSensors;
import cloud.cholewa.weather.reading.CurrentWeather;
import cloud.cholewa.weather.reading.SensorReadings;
import cloud.cholewa.weather.registry.DeviceRegistryClient;
import cloud.cholewa.weather.registry.StationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

//The station the service reads and what the components of its gateway carry. The registry is read at
//the start and every five minutes (StationRefresh), and so is the gateway, after it: the map of the
//sensors - a re-pairing gives the sensors other ids, and the gateway says nothing about it on the
//broker (no events/rpc) - and what each sensor holds now. That read is the reconciliation with the
//gateway (HAS-237): a status the broker lost (a restart of the broker, a reconnecting gateway) and a
//value that never changes (the battery, the rain for days) come into the state this way, and so does
//the first state after a deploy. Another station or gateway clears the current state: what the last
//one measured is not a reading of this one.
//A failed read keeps what was known: a database-service or a gateway away for a moment must not make
//the station unknown. The time of the last answer of the gateway is what StationWatch judges it by.
//Until the first read the station is unknown and every event is ignored
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherStation {

    //the map as read from the gateway of a station, and when the gateway answered
    private record Sensors(Station station, StationSensors sensors, Instant readAt) {
    }

    //the last read of the gateway of a station, answered or not - what the status of the read API shows
    public record GatewayCheck(Station station, Instant checkedAt, boolean answered) {
    }

    private final DeviceRegistryClient deviceRegistryClient;
    private final SensorMapLoader sensorMapLoader;
    private final CurrentWeather currentWeather;
    private final Clock clock;

    //what the registry had twice, as last said at WARN - said again only when it changes
    private final AtomicReference<String> duplicates = new AtomicReference<>("");

    //null while there is no station: before the first successful read of the registry, and after a
    //read without one (registryRead tells the two apart, for the log)
    private final AtomicReference<Station> station = new AtomicReference<>();
    private final AtomicBoolean registryRead = new AtomicBoolean();
    private final AtomicReference<Sensors> sensors = new AtomicReference<>();
    private final AtomicReference<GatewayCheck> lastCheck = new AtomicReference<>();

    public Mono<Void> refresh() {
        return Mono.defer(this::readRegistry)
            .doOnError(e -> log.warn("Refresh of the weather station from the device registry failed, the last one stays: {}",
                e.toString()))
            .onErrorResume(e -> Mono.justOrEmpty(station.get()).map(Optional::of))
            .flatMap(Mono::justOrEmpty)
            .flatMap(this::readGateway);
    }

    public Optional<Station> station() {
        return Optional.ofNullable(station.get());
    }

    //the map of the station as it is now - never one read for a station or a gateway since replaced
    public Optional<StationSensors> sensors() {
        return current().map(Sensors::sensors);
    }

    //when the gateway of the station answered last - empty until it has answered since the start, or
    //since the station or the gateway changed
    public Optional<Instant> gatewayAnsweredAt() {
        return current().map(Sensors::readAt);
    }

    //the last read of the gateway of the station as it is now - empty before the first one, and after
    //the station or the gateway changed
    public Optional<GatewayCheck> lastCheck() {
        final GatewayCheck check = lastCheck.get();
        return check != null && station().filter(check.station()::equals).isPresent()
            ? Optional.of(check)
            : Optional.empty();
    }

    private Optional<Sensors> current() {
        final Sensors read = sensors.get();
        return read != null && station().filter(read.station()::equals).isPresent()
            ? Optional.of(read)
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

    private Mono<Void> readGateway(final Station known) {
        return sensorMapLoader.load(known)
            .doOnNext(gateway -> {
                final StationSensors loaded = gateway.sensors();
                final Instant answeredAt = clock.instant();
                lastCheck.set(new GatewayCheck(known, answeredAt, true));
                final Sensors previous = sensors.getAndSet(new Sensors(known, loaded, answeredAt));
                if (previous == null || !previous.station().equals(known) || !previous.sensors().equals(loaded)) {
                    log.info("Sensors of the weather station on the gateway {}: {}", known.gatewayDeviceId(), loaded);
                }
                gateway.readings().forEach(currentWeather::seed);
                if (gateway.device() != null) {
                    SensorReadings.heardAt(gateway.device()).ifPresent(currentWeather::heard);
                    if (currentWeather.link().isEmpty()) {
                        currentWeather.link(new StationLink(gateway.device().getRssi(), gateway.device().getBattery(),
                            gateway.device().getPacketId(), LocalDateTime.now(clock)));
                    }
                }
            })
            .doOnError(e -> {
                lastCheck.set(new GatewayCheck(known, clock.instant(), false));
                log.warn("Read of the weather station from the gateway {} failed, the last map stays: {}",
                    known.gatewayDeviceId(), e.toString());
            })
            .onErrorComplete()
            .then();
    }
}
