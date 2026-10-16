package cloud.cholewa.weather.station;

import cloud.cholewa.weather.monitor.StationWatch;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

//The registry and the gateway read at the start and every five minutes, then the watch over the
//station judges what the read showed. Spring subscribes again to the same Mono for every run, which is
//why both are deferred inside. fixedDelay: the next pass waits for the last one to end. Neither part
//signals an error. Scheduling is off under the test profile
@Component
@RequiredArgsConstructor
public class StationRefresh {

    private final WeatherStation weatherStation;
    private final StationWatch stationWatch;

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT5M")
    Mono<Void> refresh() {
        return weatherStation.refresh().then(stationWatch.check());
    }
}
