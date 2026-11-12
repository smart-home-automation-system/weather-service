package cloud.cholewa.weather.air;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.weather.config.AirProperties;
import cloud.cholewa.weather.config.MonitorProperties;
import cloud.cholewa.weather.database.model.StationAlertEntity;
import cloud.cholewa.weather.database.repository.StationAlertRepository;
import cloud.cholewa.weather.model.AirSensor;
import cloud.cholewa.weather.monitor.Watch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tells the technical channel when the air quality sensor gives no valid reading (HAS-429) - the rules of
 * StationWatch for the station: an alert (error) once nothing valid was measured for
 * {@code weather.air.silent-after}, a reminder (warn) every {@code weather.monitor.reminder-interval}, one
 * info when a valid reading is back. Valid is what AirQuality took or confirmed (lastReadingAt): a sensor in
 * error, without a measurement or not answering adds nothing. Judged after every read of the sensor
 * (AirQualityPoll, every minute).
 * <p>
 * Without a valid reading since the start - or since another sensor replaced the last one - the time is
 * counted from then. The open alert is the row of station_alert with the watch AIR_SENSOR and the registry id
 * of the sensor as station_id; a row about a sensor the registry no longer has is closed without a word.
 * Notification first, row after; cut off after {@link #PUBLISH_TIMEOUT}, logged and swallowed - never failing
 * nor stalling the pass. Without a sensor in the registry nothing is judged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AirSensorWatch {

    //two of these and the read of the sensor fit the bound of the poll (50 s)
    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(20);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private record Watched(AirSensor sensor, Instant since) {
    }

    private final AirQuality airQuality;
    private final StationAlertRepository stationAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final AirProperties airProperties;
    private final MonitorProperties monitorProperties;
    private final Clock clock;

    private final AtomicReference<Watched> watched = new AtomicReference<>();

    //never signals an error; deferred - the time and the state are read at subscription
    public Mono<Void> check() {
        return Mono.defer(() -> Mono.justOrEmpty(airQuality.sensor()))
            .flatMap(sensor -> {
                final Instant now = clock.instant();
                final Instant watchedSince = watchedSince(sensor, now);
                return stationAlertRepository.findAll()
                    .filter(alert -> alert.watch() == Watch.AIR_SENSOR)
                    .filterWhen(alert -> ofThisSensor(alert, sensor))
                    .next()
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(alert -> judge(sensor, airQuality.lastReadingAt().orElse(null), watchedSince,
                        alert.orElse(null), now));
            })
            .doOnError(e -> log.warn("The open alert about the air quality sensor could not be read, checked again with the next pass: {}",
                e.toString()))
            .onErrorComplete();
    }

    //for the read API: nothing valid measured for silent-after - never measured counts as silent
    public boolean silent() {
        final Instant last = airQuality.lastReadingAt().orElse(null);
        return last == null || !last.plus(airProperties.silentAfter()).isAfter(clock.instant());
    }

    private Mono<Boolean> ofThisSensor(final StationAlertEntity alert, final AirSensor sensor) {
        if (alert.stationId() == sensor.id()) {
            return Mono.just(true);
        }
        log.info("The alert about the air quality sensor {} (since {}) is closed: the registry has the sensor {} now",
            alert.stationId(), alert.since(), sensor.id());
        return stationAlertRepository.delete(alert).thenReturn(false);
    }

    private Instant watchedSince(final AirSensor sensor, final Instant now) {
        return watched.updateAndGet(held -> held != null && held.sensor().equals(sensor) ? held : new Watched(sensor, now))
            .since();
    }

    private Mono<Void> judge(
        final AirSensor sensor,
        final Instant lastSign,
        final Instant watchedSince,
        final StationAlertEntity alert,
        final Instant now
    ) {
        final Instant quietSince = lastSign != null ? lastSign : watchedSince;
        final boolean silent = !quietSince.plus(airProperties.silentAfter()).isAfter(now);

        //a valid reading after the start of the alert ends it first, even when it is silent again by now
        final Mono<Void> due;
        if (alert != null && lastSign != null && lastSign.isAfter(alert.since())) {
            due = notificationPublisher.publishInfo("The air quality sensor %s gives valid readings again. None came from %s to %s (%s)."
                    .formatted(sensor.name(), time(alert.since()), time(lastSign), span(alert.since(), lastSign)))
                .then(stationAlertRepository.delete(alert));
        } else if (silent && alert == null) {
            final String from = (lastSign != null ? "since %s" : "since the service started watching it at %s")
                .formatted(time(quietSince));
            due = notificationPublisher.publishAlert("The air quality sensor %s has given no valid reading %s (%s)."
                    .formatted(sensor.name(), from, span(quietSince, now)))
                .then(stationAlertRepository.save(new StationAlertEntity(null, sensor.id(), Watch.AIR_SENSOR, quietSince, now)))
                .then();
        } else if (silent && !alert.lastAlertAt().plus(monitorProperties.reminderInterval()).isAfter(now)) {
            due = notificationPublisher.publishReminder("The air quality sensor %s has still given no valid reading - since %s (%s)."
                    .formatted(sensor.name(), time(alert.since()), span(alert.since(), now)))
                .then(stationAlertRepository.save(
                    new StationAlertEntity(alert.id(), alert.stationId(), Watch.AIR_SENSOR, alert.since(), now)))
                .then();
        } else {
            due = Mono.empty();
        }
        return due
            .timeout(PUBLISH_TIMEOUT)
            .doOnError(e -> log.error(
                "Notification about the air quality sensor not published, it is tried again with the next pass: {}", e.toString()))
            .onErrorComplete();
    }

    private String time(final Instant instant) {
        return TIME.format(instant.atZone(clock.getZone()));
    }

    private static String span(final Instant since, final Instant until) {
        final Duration duration = Duration.between(since, until);
        final long hours = duration.toHours();
        final int minutes = duration.toMinutesPart();
        return hours == 0 ? minutes + " min" : hours + " h " + minutes + " min";
    }
}
