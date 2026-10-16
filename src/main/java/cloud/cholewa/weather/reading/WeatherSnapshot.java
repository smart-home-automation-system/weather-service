package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.database.repository.WeatherReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
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
//Spring subscribes again to the same Mono for every run, so everything happens inside Mono.defer.
//Scheduling is off under the test profile
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherSnapshot implements SmartLifecycle {

    //the shutdown of the pod waits for the last store at most this long (the grace period is 30 s)
    static final Duration LAST_STORE = Duration.ofSeconds(10);

    private final CurrentWeather currentWeather;
    private final WeatherReadingRepository weatherReadingRepository;
    private final Clock clock;

    private final AtomicBoolean restored = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    @Scheduled(initialDelayString = "PT1M", fixedRateString = "PT1M")
    Mono<Void> snapshot() {
        return Mono.defer(this::restoreOnce).then(Mono.defer(this::storeWhenRestored));
    }

    private Mono<Void> restoreOnce() {
        if (restored.get()) {
            return Mono.empty();
        }
        return weatherReadingRepository.findFirstByOrderByIdDesc()
            .doOnNext(latest -> {
                currentWeather.restore(ReadingMapper.toReadings(latest, clock.getZone()));
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
        return Mono.justOrEmpty(currentWeather.takeSnapshot())
            .flatMap(snapshot -> Mono.fromCallable(() -> ReadingMapper.toEntity(snapshot, clock.getZone()))
                .flatMap(weatherReadingRepository::save)
                .doOnNext(saved -> log.info("Weather reading stored: {}", saved))
                .doOnError(e -> {
                    currentWeather.snapshotFailed();
                    log.warn("Weather reading measured at {} could not be stored, tried again in a minute: {}",
                        snapshot.measuredAt(), e.toString());
                })
                .onErrorComplete())
            .then();
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
