package cloud.cholewa.weather.air;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.weather.model.AirSensor;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.registry.AirSensorMapper;
import cloud.cholewa.weather.registry.DeviceRegistryClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

//The air quality outside the house (HAS-427): the BleBox airSensor of the registry and what it measured,
//in memory - apart from CurrentWeather, which is the station (its heardAt, its link, its snapshot rows):
//the sensor is no part of the station and its silence is not the station's.
//The sensor is read from the registry with every refresh of the station (StationRefresh, every 5 min); a
//failed read keeps the last one, another sensor clears what the last one measured, a registry without one
//clears it all. The sensor itself is read every minute (AirQualityPoll); a failed read keeps the readings
//and is one WARN an hour per kind of failure, never an error out of the pass.
//The readings follow CurrentWeather.seed: a metric not held yet, or another value measured strictly later,
//becomes the state; the same value at a later time confirms it (no change - for the rows of HAS-428 - but
//the current weather of the read API shows the value with that time). The poll and the read API run on other threads, hence one lock
@Slf4j
@Component
@RequiredArgsConstructor
public class AirQuality {

    static final Duration WARN_EVERY = Duration.ofHours(1);

    //the last read of the sensor, answered or not - what the status of the read API shows
    public record Check(AirSensor sensor, Instant checkedAt, boolean answered) {
    }

    private final DeviceRegistryClient deviceRegistryClient;
    private final AirSensorClient airSensorClient;
    private final Clock clock;

    private final AtomicReference<AirSensor> sensor = new AtomicReference<>();
    //a registry without a sensor is said with the first read too, then only when it changes
    private final AtomicBoolean registryRead = new AtomicBoolean();
    private final AtomicReference<Check> lastCheck = new AtomicReference<>();
    private final AtomicReference<String> duplicates = new AtomicReference<>("");
    private final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
    private final Map<Metric, Instant> confirmed = new EnumMap<>(Metric.class);
    private final Map<AirSensorCallException.Kind, Instant> warnedAt = new EnumMap<>(AirSensorCallException.Kind.class);

    //the sensor from the registry; never signals an error
    public Mono<Void> refresh() {
        return Mono.defer(() -> deviceRegistryClient.getDevices(DeviceKind.AIR_QUALITY_SENSOR))
            .doOnNext(devices -> {
                warnAboutDuplicates(AirSensorMapper.duplicates(devices));
                take(AirSensorMapper.toAirSensor(devices).orElse(null));
            })
            .doOnError(e -> log.warn("Read of the air quality sensor from the device registry failed, the last one stays: {}",
                e.toString()))
            .onErrorComplete()
            .then();
    }

    //one read of the sensor, while the registry has one; never signals an error
    public Mono<Void> poll() {
        return Mono.defer(() -> Mono.justOrEmpty(sensor.get()))
            .flatMap(known -> airSensorClient.state(known.address())
                .doOnNext(state -> answered(known, state))
                .doOnError(e -> failed(known, e))
                .onErrorComplete())
            .then();
    }

    public Optional<AirSensor> sensor() {
        return Optional.ofNullable(sensor.get());
    }

    //the last read of the sensor as it is now - empty before the first one, and after the sensor changed
    public Optional<Check> lastCheck() {
        final Check check = lastCheck.get();
        return check != null && sensor().filter(check.sensor()::equals).isPresent() ? Optional.of(check) : Optional.empty();
    }

    //the newest time a reading of the sensor was measured - taken or confirmed
    public Optional<Instant> lastReadingAt() {
        synchronized (readings) {
            return confirmed.values().stream().max(Instant::compareTo);
        }
    }

    //the state as the read API shows it: each value with the newest time it was confirmed
    public Map<Metric, Reading> confirmedReadings() {
        synchronized (readings) {
            final Map<Metric, Reading> current = new EnumMap<>(Metric.class);
            readings.forEach((metric, reading) -> current.put(metric,
                new Reading(reading.number(), reading.flag(), confirmed.getOrDefault(metric, reading.measuredAt()))));
            return current;
        }
    }

    private void take(final AirSensor read) {
        final AirSensor previous = sensor.getAndSet(read);
        final boolean first = !registryRead.getAndSet(true);
        final boolean same = read == null ? previous == null : read.equals(previous);
        if (same && !first) {
            return;
        }
        if (!same) {
            clear();
        }
        if (read == null) {
            log.warn("The device registry has no switched-on air quality sensor with a BleBox binding");
        } else {
            log.info("Air quality sensor {} ({}) read at {}", read.id(), read.name(), read.address());
        }
    }

    private void clear() {
        synchronized (readings) {
            readings.clear();
            confirmed.clear();
            warnedAt.clear();
        }
        lastCheck.set(null);
    }

    private void warnAboutDuplicates(final String found) {
        if (!found.equals(duplicates.getAndSet(found)) && !found.isEmpty()) {
            log.warn("The device registry has more than one switched-on device of a kind, the one with the lowest id is used: {}", found);
        }
    }

    private void answered(final AirSensor known, final AirStateResponse state) {
        final Instant now = clock.instant();
        synchronized (readings) {
            if (!known.equals(sensor.get())) {
                return;
            }
            AirReadings.of(state, now).forEach(this::seed);
            warnedAt.clear();
        }
        lastCheck.set(new Check(known, now, true));
    }

    private void failed(final AirSensor known, final Throwable error) {
        final Instant now = clock.instant();
        if (!known.equals(sensor.get())) {
            return;
        }
        lastCheck.set(new Check(known, now, false));
        final AirSensorCallException.Kind kind = error instanceof AirSensorCallException call
            ? call.getKind()
            : AirSensorCallException.Kind.INVALID_BODY;
        final boolean warn;
        synchronized (readings) {
            final Instant last = warnedAt.get(kind);
            warn = last == null || !last.plus(WARN_EVERY).isAfter(now);
            if (warn) {
                warnedAt.put(kind, now);
            }
        }
        if (warn) {
            log.warn("Read of the air quality sensor {} at {} failed, the last readings stay: {}",
                known.id(), known.address(), error.getMessage());
        }
    }

    //under the lock of the map - the rule of CurrentWeather.seed
    private void seed(final Metric metric, final Reading reading) {
        final Reading held = readings.get(metric);
        if (held == null || reading.measuredAt().isAfter(held.measuredAt()) && !sameValue(held, reading)) {
            readings.put(metric, reading);
            confirmed.put(metric, reading.measuredAt());
        } else if (sameValue(held, reading)) {
            confirmed.merge(metric, reading.measuredAt(), (was, next) -> next.isAfter(was) ? next : was);
        }
    }

    private static boolean sameValue(final Reading held, final Reading reading) {
        return reading.sameAs(new Reading(held.number(), held.flag(), reading.measuredAt()));
    }
}
