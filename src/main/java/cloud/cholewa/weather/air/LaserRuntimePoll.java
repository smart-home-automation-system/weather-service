package cloud.cholewa.weather.air;

import cloud.cholewa.commons.scheduling.ScheduledPass;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

//The run time of the laser read once an hour (HAS-437) - not configurable: the counter moves by an hour, and the
//warning is about hundreds of them. The pass looks every five minutes and LaserRuntime asks the sensor only when its
//hour is up: a start whose first read of the registry failed waits for the next refresh, not for an hour. Its own pass, apart from AirQualityPoll, whose minute stays as it is. The
//first pass waits two minutes - the first refresh of the station reads the sensor from the registry - and brings
//the counter back from the table before it reads the sensor. fixedDelay: the next pass waits for the last one to
//end; LaserRuntime.check is deferred inside, since Spring subscribes again to the same Mono for every run.
//Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class LaserRuntimePoll {

    static final String POLL_INTERVAL = "PT5M";

    //one call with a response timeout of 5 s, two notifications cut off at 20 s together, one row - a pass beyond
    //50 s is hung and ends there
    static final Duration LIMIT = Duration.ofSeconds(50);

    private final LaserRuntime laserRuntime;

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = POLL_INTERVAL)
    Mono<Void> poll() {
        return ScheduledPass.bounded("laser run time poll", LIMIT, laserRuntime::check);
    }
}
