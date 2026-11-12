package cloud.cholewa.weather.air;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.home.model.HouseholdEvent;
import cloud.cholewa.home.model.HouseholdNotification;
import cloud.cholewa.weather.config.AirProperties;
import cloud.cholewa.weather.database.model.AirQualityAlertEntity;
import cloud.cholewa.weather.database.repository.AirQualityAlertRepository;
import cloud.cholewa.weather.model.AirSensor;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The episodes of smog outside the house (HAS-429): a level of the air (the GIOS scale of the sensor) at
 * {@code weather.air.smog-level} or above, held for {@code weather.air.smog-after}, opens one; a level at
 * {@code weather.air.clear-level} or below, held as long, closes it - the levels between hold the state.
 * The hold is judged by the times the sensor measured, never by the clock alone: a sensor that falls silent
 * on a bad level opens nothing.
 * <p>
 * One household message at the start ({@code smog warning}) and one at the end ({@code smog over}) - no
 * reminders, no SMS (owner, 2026-11-12): a HouseholdNotification of smart-home-sdk as JSON through
 * NotificationPublisher.publishHousehold, the subject the sensor as the registry names it, the particles of
 * the reading. The episodes are rows of air_quality_alert (V4): a restart in the middle goes on with the open
 * one, never a second message. Notification first, row after - the state moves on only once the broker took
 * the message; cut off after {@link #PUBLISH_TIMEOUT}, logged and swallowed. An open episode of a sensor the
 * registry no longer has is closed without a word. Judged after every read of the sensor (AirQualityPoll).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmogWatch {

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(20);

    private final AirQuality airQuality;
    private final AirQualityAlertRepository airQualityAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final JsonMapper jsonMapper;
    private final AirProperties properties;
    private final Clock clock;

    //since when the level has been at the smog level / at the clear level without a break - by the sensor's times
    private final AtomicReference<Instant> badSince = new AtomicReference<>();
    private final AtomicReference<Instant> clearSince = new AtomicReference<>();
    //the time of the level last judged - a gap longer than smog-after breaks both holds
    private final AtomicReference<Instant> lastLevelAt = new AtomicReference<>();
    //the open episode as last read or written - what the read API shows
    private final AtomicReference<AirQualityAlertEntity> open = new AtomicReference<>();

    //never signals an error; deferred - the state is read at subscription
    public Mono<Void> check() {
        return Mono.defer(() -> Mono.justOrEmpty(airQuality.sensor()))
            .flatMap(sensor -> airQualityAlertRepository.findFirstByEndedAtIsNull()
                .flatMap(episode -> ofThisSensor(episode, sensor))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(episode -> {
                    open.set(episode.orElse(null));
                    final Map<Metric, Reading> readings = airQuality.confirmedReadings();
                    return Mono.justOrEmpty(readings.get(Metric.AIR_QUALITY_LEVEL))
                        .flatMap(level -> judge(sensor, episode.orElse(null), level, readings));
                }))
            .doOnError(e -> log.warn("The episode of smog could not be read, checked again with the next pass: {}", e.toString()))
            .onErrorComplete();
    }

    //the open episode for the read API - empty while the air is fine
    public Optional<AirQualityAlertEntity> openEpisode() {
        return Optional.ofNullable(open.get());
    }

    private Mono<AirQualityAlertEntity> ofThisSensor(final AirQualityAlertEntity episode, final AirSensor sensor) {
        if (episode.sensorId() == sensor.id()) {
            return Mono.just(episode);
        }
        log.info("The episode of smog of the air quality sensor {} (since {}) is closed: the registry has the sensor {} now",
            episode.sensorId(), episode.since(), sensor.id());
        return airQualityAlertRepository.save(episode.toBuilder().endedAt(clock.instant()).build())
            .then(Mono.<AirQualityAlertEntity>empty());
    }

    private Mono<Void> judge(final AirSensor sensor, final AirQualityAlertEntity episode, final Reading level,
                             final Map<Metric, Reading> readings) {
        final int value = level.number().intValue();
        final Instant at = level.measuredAt();
        hold(value, at);
        final Integer pm10 = whole(readings.get(Metric.PM10));
        final Integer pm25 = whole(readings.get(Metric.PM25));

        final Mono<Void> due;
        if (episode == null && held(badSince.get(), at)) {
            final Instant since = badSince.get();
            due = publish(sensor, HouseholdEvent.SMOG_WARNING, since, pm10, pm25)
                .then(airQualityAlertRepository.save(AirQualityAlertEntity.builder()
                    .sensorId(sensor.id()).since(since).level(value).peakPm10(pm10).peakPm25(pm25).build()))
                .doOnNext(saved -> {
                    open.set(saved);
                    log.info("Smog outside since {}: level {}, PM10 {}, PM2.5 {}", since, value, pm10, pm25);
                })
                .then();
        } else if (episode != null && held(clearSince.get(), at)) {
            due = publish(sensor, HouseholdEvent.SMOG_OVER, at, pm10, pm25)
                .then(airQualityAlertRepository.save(episode.toBuilder().endedAt(at).build()))
                .doOnNext(saved -> {
                    open.set(null);
                    clearSince.set(null);
                    log.info("Smog outside over at {}: level {}, PM10 {}, PM2.5 {}", at, value, pm10, pm25);
                })
                .then();
        } else if (episode != null && rose(episode, value, pm10, pm25)) {
            due = airQualityAlertRepository.save(episode.toBuilder()
                    .level(Math.max(episode.level(), value))
                    .peakPm10(max(episode.peakPm10(), pm10))
                    .peakPm25(max(episode.peakPm25(), pm25))
                    .build())
                .doOnNext(open::set)
                .then();
        } else {
            due = Mono.empty();
        }
        return due
            .timeout(PUBLISH_TIMEOUT)
            .doOnError(e -> log.error("The episode of smog was not moved on, it is tried again with the next pass: {}", e.toString()))
            .onErrorComplete();
    }

    //the level read now: at the smog level it keeps (or starts) the bad hold, at the clear level the clear one;
    //between the two neither holds. A gap in the measurements longer than smog-after - a silent sensor, the reading
    //of the latest row restored after a restart - is no hold: both start anew from this reading
    private void hold(final int value, final Instant at) {
        final Instant last = lastLevelAt.getAndSet(at);
        if (last != null && at.isAfter(last.plus(properties.smogAfter()))) {
            badSince.set(null);
            clearSince.set(null);
        }
        if (value >= properties.smogLevel()) {
            badSince.accumulateAndGet(at, (held, next) -> held == null ? next : held);
            clearSince.set(null);
        } else if (value <= properties.clearLevel()) {
            clearSince.accumulateAndGet(at, (held, next) -> held == null ? next : held);
            badSince.set(null);
        } else {
            badSince.set(null);
            clearSince.set(null);
        }
    }

    private boolean held(final Instant since, final Instant at) {
        return since != null && !at.isBefore(since.plus(properties.smogAfter()));
    }

    private static boolean rose(final AirQualityAlertEntity episode, final int level, final Integer pm10, final Integer pm25) {
        return level > episode.level() || above(pm10, episode.peakPm10()) || above(pm25, episode.peakPm25());
    }

    private static boolean above(final Integer value, final Integer peak) {
        return value != null && (peak == null || value > peak);
    }

    private static Integer max(final Integer peak, final Integer value) {
        return above(value, peak) ? value : peak;
    }

    private static Integer whole(final Reading reading) {
        return reading == null ? null : reading.number().intValue();
    }

    private Mono<Void> publish(final AirSensor sensor, final HouseholdEvent event, final Instant at,
                               final Integer pm10, final Integer pm25) {
        return Mono.fromCallable(() -> jsonMapper.writeValueAsString(HouseholdNotification.builder()
                .event(event)
                .subject(sensor.name())
                .deviceId(sensor.id())
                .occurredAt(LocalDateTime.ofInstant(at, clock.getZone()))
                .reminder(0)
                .pm10(pm10)
                .pm25(pm25)
                .build()))
            .flatMap(notificationPublisher::publishHousehold);
    }
}
