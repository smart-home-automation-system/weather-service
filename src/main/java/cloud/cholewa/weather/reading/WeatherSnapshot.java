package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.database.repository.WeatherReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

//Once a minute the current state becomes a row of weather_reading - when anything arrived since the
//last row. The first pass restores the state from the latest row first, so that a metric the station
//has not changed since the restart is known at once (a calm night sends no wind for hours); a failed
//restore is tried again by the next pass, a failed insert keeps the readings for the next one.
//The row is logged at INFO: one line a minute, and the only way to compare the state with
//BTHomeSensor.GetStatus of the gateway until the read API comes (HAS-238).
//Spring subscribes again to the same Mono for every run, so everything happens inside Mono.defer.
//Scheduling is off under the test profile
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherSnapshot {

    private final CurrentWeather currentWeather;
    private final WeatherReadingRepository weatherReadingRepository;

    private final AtomicBoolean restored = new AtomicBoolean();

    @Scheduled(initialDelayString = "PT1M", fixedRateString = "PT1M")
    Mono<Void> snapshot() {
        return Mono.defer(this::restoreOnce).then(Mono.defer(this::store));
    }

    private Mono<Void> restoreOnce() {
        if (restored.get()) {
            return Mono.empty();
        }
        return weatherReadingRepository.findFirstByOrderByIdDesc()
            .doOnNext(latest -> {
                currentWeather.restore(ReadingMapper.toReadings(latest));
                log.info("Weather state restored from the reading {} measured at {}", latest.id(), latest.measuredAt());
            })
            .doOnSuccess(ignored -> restored.set(true))
            .doOnError(e -> log.warn("Restore of the weather state from the database failed, tried again in a minute: {}",
                e.toString()))
            .onErrorComplete()
            .then();
    }

    private Mono<Void> store() {
        return Mono.justOrEmpty(currentWeather.takeSnapshot())
            .flatMap(snapshot -> Mono.fromCallable(() -> ReadingMapper.toEntity(snapshot))
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
}
