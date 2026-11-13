package cloud.cholewa.weather.air;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.weather.config.LaserProperties;
import cloud.cholewa.weather.database.model.AirSensorRuntimeEntity;
import cloud.cholewa.weather.database.repository.AirSensorRuntimeRepository;
import cloud.cholewa.weather.model.AirSensor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The hours the laser of the air quality sensor has run (HAS-437): {@code GET /api/air/runtime} once an hour
 * ({@link #READ_EVERY}; LaserRuntimePoll looks every five minutes, so that a start without the sensor - the registry
 * not read yet - waits minutes, not an hour), so that the household orders a new sensor before the old one wears out - BleBox gives its
 * measuring system about 8000 hours, less outdoors.
 * <p>
 * The counter is held in memory with the time of its read, and stored in the one row of the sensor in
 * air_sensor_runtime (V5) only when it changes - another hour, or another warning. The row brings it back with the
 * first pass after a start, before the sensor is read. A failed read ({}, a fraction, the sensor away) is one WARN a
 * pass - the pass is hourly - and keeps the last value.
 * <p>
 * The technical channel, never the household and never an SMS: one warning (warn) when the counter reaches
 * {@code weather.air.laser.warn-at-hours}, again every {@link #WARN_EVERY_HOURS} hours more; one info when the
 * counter drops - a sensor replaced or reset - after which the warnings start anew. The step of the last warning is
 * stored with the row, so a restart never repeats it. Notification first, row after: a notification the broker did
 * not take leaves the row and the memory as they were, and the next pass tells it again; cut off after
 * {@link #PUBLISH_TIMEOUT}, logged and swallowed - never failing the pass. Without a sensor in the registry nothing
 * is read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LaserRuntime {

    static final long WARN_EVERY_HOURS = 500;

    //how often the sensor is asked - a failed read too, so its WARN comes once an hour at most
    static final Duration READ_EVERY = Duration.ofHours(1);

    //two notifications of 20 s and the read of the sensor fit the bound of the poll (50 s)
    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(20);

    //what the read API shows: the hours of the sensor, when they were read, the life of the laser and whether the
    //counter has reached the warning
    public record Laser(long sensorId, long hours, Instant readAt, long lifeHours, boolean warn) {
    }

    //the last time a sensor was asked, answered or not
    private record Attempt(long sensorId, Instant at) {
    }

    //the row as stored, and the time of the last read - newer than read_at when the counter stood still
    private record Held(AirSensorRuntimeEntity row, Instant readAt) {
    }

    private final AirQuality airQuality;
    private final AirSensorClient airSensorClient;
    private final AirSensorRuntimeRepository airSensorRuntimeRepository;
    private final NotificationPublisher notificationPublisher;
    private final LaserProperties properties;
    private final Clock clock;

    private final AtomicReference<Held> held = new AtomicReference<>();
    private final AtomicReference<Attempt> attempt = new AtomicReference<>();

    //never signals an error; deferred - the sensor and the time are read at subscription
    public Mono<Void> check() {
        return Mono.defer(() -> Mono.justOrEmpty(airQuality.sensor()))
            .flatMap(sensor -> known(sensor)
                .filter(before -> due(sensor))
                .flatMap(before -> airSensorClient.runtimeHours(sensor.address())
                    .doOnError(e -> log.warn("Read of the run time of the laser of the air quality sensor {} at {} failed, the last value stays: {}",
                        sensor.id(), sensor.address(), e.getMessage()))
                    .onErrorComplete(AirSensorCallException.class)
                    .flatMap(hours -> judge(sensor, before.orElse(null), hours, clock.instant()))))
            .doOnError(e -> log.warn("The run time of the laser could not be read or stored, checked again with the next pass: {}",
                e.toString()))
            .onErrorComplete();
    }

    //the counter of the sensor the registry has now - empty before its first read or restore
    public Optional<Laser> laser() {
        return Optional.ofNullable(held.get())
            .filter(known -> airQuality.sensor().filter(sensor -> sensor.id() == known.row().sensorId()).isPresent())
            .map(known -> new Laser(known.row().sensorId(), known.row().hours(), known.readAt(), properties.lifeHours(),
                known.row().hours() >= properties.warnAtHours()));
    }

    //whether the sensor is to be asked now - never asked, another sensor, or the last time an hour ago; marks the attempt
    private boolean due(final AirSensor sensor) {
        final Instant now = clock.instant();
        final Attempt last = attempt.get();
        final boolean due = last == null || last.sensorId() != sensor.id() || !last.at().plus(READ_EVERY).isAfter(now);
        if (due) {
            attempt.set(new Attempt(sensor.id(), now));
        }
        return due;
    }

    //the row of this sensor: held, or read back from the table at the first pass after a start (or another sensor)
    private Mono<Optional<AirSensorRuntimeEntity>> known(final AirSensor sensor) {
        return Mono.justOrEmpty(held.get())
            .map(Held::row)
            .filter(row -> row.sensorId() == sensor.id())
            .switchIfEmpty(Mono.defer(() -> airSensorRuntimeRepository.findBySensorId(sensor.id())
                .doOnNext(row -> {
                    held.set(new Held(row, row.readAt()));
                    log.info("Run time of the laser of the air quality sensor {} restored: {} h, read at {}",
                        sensor.id(), row.hours(), row.readAt());
                })))
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    private Mono<Void> judge(final AirSensor sensor, final AirSensorRuntimeEntity before, final long hours, final Instant now) {
        final boolean dropped = before != null && hours < before.hours();
        final Long warnedBefore = dropped || before == null ? null : before.warnedAtHours();
        final Long due = dueWarning(hours);
        final boolean warning = due != null && (warnedBefore == null || due > warnedBefore);
        final Long warnedAt = warning ? due : warnedBefore;

        final AirSensorRuntimeEntity row = before == null
            ? AirSensorRuntimeEntity.builder().sensorId(sensor.id()).hours(hours).readAt(now).warnedAtHours(warnedAt).build()
            : before.toBuilder().hours(hours).readAt(now).warnedAtHours(warnedAt).build();
        final boolean changed = before == null || hours != before.hours() || !Objects.equals(warnedAt, before.warnedAtHours());

        //the drop first, then the warning of the new count - one after the other, each only when due
        final Mono<Void> told = Mono.just(dropped).filter(Boolean::booleanValue)
            .flatMap(yes -> dropped(sensor, before.hours(), hours))
            .then(Mono.just(warning).filter(Boolean::booleanValue)
                .flatMap(yes -> warned(sensor, hours, warnedBefore != null)))
            .timeout(PUBLISH_TIMEOUT);

        return told
            .then(Mono.just(row).filter(changing -> changed).flatMap(airSensorRuntimeRepository::save))
            .switchIfEmpty(Mono.justOrEmpty(before))
            .doOnNext(stored -> held.set(new Held(stored, now)))
            .then()
            .doOnError(e -> log.error("The run time of the laser was not told or stored, it is tried again with the next pass: {}",
                e.toString()))
            .onErrorComplete();
    }

    //the step the counter has reached: warn-at-hours, then every 500 hours more; null below it
    private Long dueWarning(final long hours) {
        final long warnAt = properties.warnAtHours();
        return hours < warnAt ? null : warnAt + (hours - warnAt) / WARN_EVERY_HOURS * WARN_EVERY_HOURS;
    }

    private Mono<Void> dropped(final AirSensor sensor, final long was, final long hours) {
        return notificationPublisher.publishInfo("The laser counter of the air quality sensor %s dropped from %d h to %d h: the sensor was replaced or reset."
            .formatted(sensor.name(), was, hours))
            .doOnSuccess(told -> log.info("The laser counter of the air quality sensor {} dropped from {} h to {} h: a replaced or reset sensor",
                sensor.id(), was, hours));
    }

    private Mono<Void> warned(final AirSensor sensor, final long hours, final boolean again) {
        final String text = (again
            ? "The laser of the air quality sensor %s has run %d h now, of the about %d h of its life. Order a new sensor."
            : "The laser of the air quality sensor %s has run %d h, of the about %d h of its life. Order a new sensor.")
            .formatted(sensor.name(), hours, properties.lifeHours());
        return notificationPublisher.publishReminder(text);
    }
}
