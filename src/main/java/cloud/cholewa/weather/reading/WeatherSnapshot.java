package cloud.cholewa.weather.reading;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.weather.air.AirQuality;
import cloud.cholewa.weather.database.repository.WeatherReadingRepository;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

//Once a minute the current state becomes a row of weather_reading - when anything changed since the
//last row. Nothing is stored before the state was restored from the latest row: a row of the few
//readings that came since the restart would become the latest one, and the next restore would read it
//instead of the whole state before it. A failed restore is tried again by the next pass, a failed
//insert keeps the readings for the next one.
//When the pod stops, the state is stored once more, after the listener has stopped (a higher phase
//stops first, the container of the listener is at the top): the gateway publishes a sensor only on a
//change, so a change the pod took off the queue and never stored would be lost until the next one.
//The row is logged at INFO: one line a minute, and the way to compare the state with
//BTHomeSensor.GetStatus of the gateway until the read API comes (HAS-238).
//The row is the whole state - the station and, since HAS-428, the air quality (AirQuality, held apart from
//the station): a change of either writes it, and the latest row restores both, each its own metrics.
//Spring subscribes again to the same Mono for every run, so everything happens inside Mono.defer.
//Scheduling is off under the test profile
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherSnapshot implements SmartLifecycle {

    //the shutdown of the pod waits for the last store at most this long (the grace period is 30 s)
    static final Duration LAST_STORE = Duration.ofSeconds(10);
    //Bounded (HAS-304): one read and one insert a minute - a pass beyond 50 s is hung on a dead connection, and
    //ends there; a snapshot cut on its way keeps its readings for the next pass. Below the rate: fixedRate does
    //not wait for the last pass, so an unbounded hung one would only add up
    static final Duration LIMIT = Duration.ofSeconds(50);

    private final CurrentWeather currentWeather;
    private final AirQuality airQuality;
    private final WeatherReadingRepository weatherReadingRepository;
    private final Clock clock;

    private final AtomicBoolean restored = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(initialDelayString = "PT1M", fixedRateString = "PT1M")
    Mono<Void> snapshot() {
        return ScheduledPass.bounded("weather snapshot", LIMIT,
            () -> Mono.defer(this::restoreOnce).then(Mono.defer(this::storeWhenRestored)));
    }

    private Mono<Void> restoreOnce() {
        if (restored.get()) {
            return Mono.empty();
        }
        return weatherReadingRepository.findFirstByOrderByIdDesc()
            .doOnNext(latest -> {
                final Map<Metric, Reading> stored = ReadingMapper.toReadings(latest, clock.getZone());
                currentWeather.restore(only(stored, Metric.Source.BTHOME));
                airQuality.restore(only(stored, Metric.Source.HTTP));
                log.info("Weather state restored from the reading {} measured at {}", latest.id(), latest.measuredAt());
            })
            .doOnSuccess(ignored -> restored.set(true))
            .doOnError(e -> log.warn("Restore of the weather state from the database failed, tried again in a minute: {}",
                e.toString()))
            .onErrorComplete()
            .then();
    }

    private Mono<Void> storeWhenRestored() {
        return restored.get() ? store() : Mono.empty();
    }

    private Mono<Void> store() {
        return Mono.fromCallable(this::takeSnapshot)
            .flatMap(Mono::justOrEmpty)
            .flatMap(snapshot -> Mono.fromCallable(() -> ReadingMapper.toEntity(snapshot, clock.getZone()))
                .flatMap(weatherReadingRepository::save)
                .doOnNext(saved -> log.info("Weather reading stored: {}", saved))
                //cut by the bound of the pass: the row may or may not be stored - the next pass stores the state again
                .doOnCancel(this::snapshotFailed)
                .doOnError(e -> {
                    snapshotFailed();
                    log.warn("Weather reading measured at {} could not be stored, tried again in a minute: {}",
                        snapshot.measuredAt(), e.toString());
                })
                .onErrorComplete())
            .then();
    }

    //the state of the station and the air quality as one row, or nothing when neither changed since the last one;
    //the newest measurement of the two is the time of the row
    private Optional<CurrentWeather.Snapshot> takeSnapshot() {
        final Optional<CurrentWeather.Snapshot> station = currentWeather.takeSnapshot();
        final boolean airChanged = airQuality.takeChange();
        final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
        readings.putAll(station.map(CurrentWeather.Snapshot::readings).orElseGet(currentWeather::readings));
        readings.putAll(airQuality.readings());
        if ((station.isEmpty() && !airChanged) || readings.isEmpty()) {
            return Optional.empty();
        }
        final Instant newest = readings.values().stream().map(Reading::measuredAt).max(Instant::compareTo).orElseThrow();
        return Optional.of(new CurrentWeather.Snapshot(newest, readings));
    }

    private void snapshotFailed() {
        currentWeather.snapshotFailed();
        airQuality.snapshotFailed();
    }

    private static Map<Metric, Reading> only(final Map<Metric, Reading> readings, final Metric.Source source) {
        final Map<Metric, Reading> of = new EnumMap<>(Metric.class);
        readings.forEach((metric, reading) -> {
            if (metric.source() == source) {
                of.put(metric, reading);
            }
        });
        return of;
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        running.set(false);
    }

    //asynchronous: the context goes on stopping the rest once the callback runs - never a block on
    //the thread of the shutdown
    @Override
    public void stop(final Runnable callback) {
        running.set(false);
        storeWhenRestored()
            .timeout(LAST_STORE)
            .doOnError(e -> log.warn("The last weather reading was not stored before the shutdown: {}", e.toString()))
            .onErrorComplete()
            .doFinally(signal -> callback.run())
            .subscribe();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    //below the container of the listener (Integer.MAX_VALUE), so it stops after the listener
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }
}
