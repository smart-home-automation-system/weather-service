package cloud.cholewa.weather.reading;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import cloud.cholewa.weather.config.RetentionProperties;
import cloud.cholewa.weather.database.repository.WeatherReadingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

//Every night at 03:10 house time the rows of weather_reading older than weather.retention (730 days)
//are deleted - never the latest one, which the state is restored from at the start. Spring subscribes
//again to the same Mono for every run, so the time is read inside Mono.defer. A failed delete is
//said at WARN and tried again the next night. Scheduling is off under the test profile
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherRetention {

    //Bounded (HAS-304): one DELETE of the rows of two years - a pass beyond a quarter of an hour is hung, and
    //ends there; the next night deletes again
    static final Duration LIMIT = Duration.ofMinutes(15);

    private final WeatherReadingRepository weatherReadingRepository;
    private final RetentionProperties properties;
    private final Clock clock;

    @Scheduled(cron = "0 10 3 * * *")
    Mono<Void> purge() {
        return ScheduledPass.bounded("weather retention", LIMIT, this::purgeOnce);
    }

    private Mono<Void> purgeOnce() {
        return Mono.defer(() -> {
                final LocalDateTime before = LocalDateTime.now(clock).minus(properties.retention());
                return weatherReadingRepository.deleteOlderThan(before)
                    .doOnNext(deleted -> log.info("Weather readings measured before {} deleted: {}", before, deleted));
            })
            .doOnError(e -> log.warn("Weather readings past the retention could not be deleted, tried again tomorrow: {}",
                e.toString()))
            .onErrorComplete()
            .then();
    }
}
