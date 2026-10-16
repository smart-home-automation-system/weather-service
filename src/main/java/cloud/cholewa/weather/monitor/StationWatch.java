package cloud.cholewa.weather.monitor;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.weather.config.MonitorProperties;
import cloud.cholewa.weather.database.model.StationAlertEntity;
import cloud.cholewa.weather.database.repository.StationAlertRepository;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.reading.CurrentWeather;
import cloud.cholewa.weather.station.WeatherStation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tells the household when the weather station goes silent and when its gateway stops answering:
 * an alert (level error) once it has lasted {@code weather.monitor.silent-after}, a reminder (warn)
 * every {@code weather.monitor.reminder-interval} while it lasts, and one info when it is over.
 * Checked after every refresh of the station (StationRefresh, every five minutes), so the read of the
 * gateway it judges by is the one just made.
 * <ul>
 * <li>{@link Watch#STATION}: the newest time the gateway heard the station ({@code heardAt} of
 * CurrentWeather) - a reading from the broker or from the read of the gateway, or the status of its
 * bthomedevice;</li>
 * <li>{@link Watch#GATEWAY}: the last answer of the gateway to the read of its components. A gateway
 * that does not answer is silent on the broker too, so this alert comes with the other one.</li>
 * </ul>
 * Without a sign of life since the start - or since another station replaced the last one - the time
 * is counted from then. Over means a sign of life after the start of the alert, nothing less: a new
 * station not heard yet ends nothing. A sign of life after the start of an alert ends it even when the
 * station is silent again by the pass that sees it (the pod was away) - the next pass opens a new one.
 * <p>
 * The open alerts are rows of station_alert, so a restart in the middle of an outage goes on with
 * the reminders. The notification goes first, the row after it: when the write fails, the next pass
 * repeats the message - a duplicate, never a silence. A notification must neither fail nor stall the
 * pass: each watch is cut off after {@link #PUBLISH_TIMEOUT} and its error is logged and swallowed;
 * the state moves on only once the broker has taken the message. A row is of one station: an alert about
 * a station the registry no longer has is closed without a word. Without a station (the registry has
 * none) nothing is judged and the rows stay.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StationWatch {

    //the publisher bounds the wait for the confirm of the broker, not a send that hangs before it
    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private record Watched(Station station, Instant since) {
    }

    private final WeatherStation weatherStation;
    private final CurrentWeather currentWeather;
    private final StationAlertRepository stationAlertRepository;
    private final NotificationPublisher notificationPublisher;
    private final MonitorProperties properties;
    private final Clock clock;

    //the station watched and since when - the time counted from while it has given no sign of life
    private final AtomicReference<Watched> watched = new AtomicReference<>();

    //never signals an error. Deferred: the Mono of the scheduled refresh is subscribed again for every
    //run, so the time and the state are read at subscription
    public Mono<Void> check() {
        return Mono.defer(() -> Mono.justOrEmpty(weatherStation.station()))
            .flatMap(station -> {
                final Instant now = clock.instant();
                final Instant watchedSince = watchedSince(station, now);
                return stationAlertRepository.findAll()
                    .filterWhen(alert -> ofThisStation(alert, station))
                    .collectMap(StationAlertEntity::watch)
                    .flatMap(alerts -> Flux.concat(
                            judge(Watch.STATION, station, currentWeather.heardAt().orElse(null), watchedSince, alerts, now),
                            judge(Watch.GATEWAY, station, weatherStation.gatewayAnsweredAt().orElse(null), watchedSince, alerts, now))
                        .then());
            })
            .doOnError(e -> log.warn("The open alerts about the weather station could not be read, checked again with the next pass: {}",
                e.toString()))
            .onErrorComplete();
    }

    //an alert about a station the registry no longer has is closed without a word: no reading of the new
    //one says anything about the outage of the old one
    private Mono<Boolean> ofThisStation(final StationAlertEntity alert, final Station station) {
        if (alert.stationId() == station.stationId()) {
            return Mono.just(true);
        }
        log.info("The {} alert about the weather station {} (since {}) is closed: the registry has the station {} now",
            alert.watch(), alert.stationId(), alert.since(), station.stationId());
        return stationAlertRepository.delete(alert).thenReturn(false);
    }

    private Instant watchedSince(final Station station, final Instant now) {
        return watched.updateAndGet(held -> held != null && held.station().equals(station) ? held : new Watched(station, now))
            .since();
    }

    private Mono<Void> judge(
        final Watch watch,
        final Station station,
        final Instant lastSign,
        final Instant watchedSince,
        final Map<Watch, StationAlertEntity> alerts,
        final Instant now
    ) {
        final StationAlertEntity alert = alerts.get(watch);
        final Instant quietSince = lastSign != null ? lastSign : watchedSince;
        final boolean silent = !quietSince.plus(properties.silentAfter()).isAfter(now);

        //a sign of life after the start of the alert ends it first, even when it is silent again by now
        //(passes missed while the pod was away): the next pass opens a new one, counted from that sign
        final Mono<Void> due;
        if (alert != null && lastSign != null && lastSign.isAfter(alert.since())) {
            due = notificationPublisher.publishInfo(recoveryText(watch, station, alert.since(), lastSign))
                .then(stationAlertRepository.delete(alert));
        } else if (silent && alert == null) {
            due = notificationPublisher.publishAlert(alertText(watch, station, quietSince, lastSign != null, now))
                .then(stationAlertRepository.save(new StationAlertEntity(null, station.stationId(), watch, quietSince, now)))
                .then();
        } else if (silent && !alert.lastAlertAt().plus(properties.reminderInterval()).isAfter(now)) {
            due = notificationPublisher.publishReminder(reminderText(watch, station, alert.since(), now))
                .then(stationAlertRepository.save(
                    new StationAlertEntity(alert.id(), alert.stationId(), watch, alert.since(), now)))
                .then();
        } else {
            due = Mono.empty();
        }
        return due
            .timeout(PUBLISH_TIMEOUT)
            .doOnError(e -> log.error(
                "Notification about the {} watch of the weather station not published, it is tried again with the next pass: {}",
                watch, e.toString()))
            .onErrorComplete();
    }

    //heard false: no sign of life since the watch started - the outage may be older, and the text says so
    private String alertText(
        final Watch watch,
        final Station station,
        final Instant since,
        final boolean heard,
        final Instant now
    ) {
        final String from = (heard ? "since %s" : "since the service started watching it at %s").formatted(time(since));
        return switch (watch) {
            case STATION -> "The weather station %s has sent no reading %s (%s)."
                .formatted(station.stationName(), from, span(since, now));
            case GATEWAY -> "The gateway %s of the weather station has not answered %s (%s)."
                .formatted(station.gatewayDeviceId(), from, span(since, now));
        };
    }

    private String reminderText(final Watch watch, final Station station, final Instant since, final Instant now) {
        return switch (watch) {
            case STATION -> "The weather station %s has still sent no reading - since %s (%s)."
                .formatted(station.stationName(), time(since), span(since, now));
            case GATEWAY -> "The gateway %s of the weather station has still not answered - since %s (%s)."
                .formatted(station.gatewayDeviceId(), time(since), span(since, now));
        };
    }

    private String recoveryText(final Watch watch, final Station station, final Instant since, final Instant until) {
        return switch (watch) {
            case STATION -> "The weather station %s is sending readings again. None came from %s to %s (%s)."
                .formatted(station.stationName(), time(since), time(until), span(since, until));
            case GATEWAY -> "The gateway %s of the weather station answers again. It did not from %s to %s (%s)."
                .formatted(station.gatewayDeviceId(), time(since), time(until), span(since, until));
        };
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
