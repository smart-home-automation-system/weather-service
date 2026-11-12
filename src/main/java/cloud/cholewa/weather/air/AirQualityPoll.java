package cloud.cholewa.weather.air;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The air sensor read every minute (HAS-427) - not configurable: the sensor measures every few minutes, and
//a minute keeps /current within a minute of it. fixedDelay: the next pass waits for the last one to end.
//The first pass waits for the first refresh of the station, which reads the sensor from the registry.
//Spring subscribes again to the same Mono for every run, which is why the pass is deferred inside
//(AirQuality.poll). After the read the sensor and the smog are judged (HAS-429: AirSensorWatch, SmogWatch),
//each never failing the pass. Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class AirQualityPoll {

    static final String POLL_INTERVAL = "PT1M";

    //Bounded (HAS-304): one call with a response timeout of 5 s and two notifications cut off at 20 s each - a pass
    //beyond 50 s is hung and ends there
    static final Duration LIMIT = Duration.ofSeconds(50);

    private final AirQuality airQuality;
    private final AirSensorWatch airSensorWatch;
    private final SmogWatch smogWatch;

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = POLL_INTERVAL)
    Mono<Void> poll() {
        return ScheduledPass.bounded("air quality poll", LIMIT,
            () -> airQuality.poll().then(airSensorWatch.check()).then(smogWatch.check()));
    }
}
