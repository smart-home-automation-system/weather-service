package cloud.cholewa.weather.api;

import cloud.cholewa.weather.api.model.CurrentMetric;
import cloud.cholewa.weather.api.model.StationStatus;
import cloud.cholewa.weather.api.model.WeatherHistoryReply;
import cloud.cholewa.weather.current.CurrentWeatherQuery;
import cloud.cholewa.weather.history.WeatherHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

//The read API of the weather (HAS-238), under the base path /home/weather: the dashboard, the blinds
//schedules and the overview read the weather here. Reads only
@Slf4j
@RestController
@RequiredArgsConstructor
public class WeatherController {

    static final String LOCAL_DATE_TIME = "yyyy-MM-dd'T'HH:mm:ss";

    private final CurrentWeatherQuery currentWeatherQuery;
    private final WeatherHistoryService weatherHistoryService;

    @GetMapping("current")
    Mono<Map<String, CurrentMetric>> current() {
        return Mono.fromSupplier(currentWeatherQuery::current);
    }

    @GetMapping("status")
    Mono<StationStatus> status() {
        return Mono.fromSupplier(currentWeatherQuery::status);
    }

    //from and to are local date-times without an offset (2026-10-09T00:00:00), read by the clock of
    //the house - the rules of the hot-water history. A pattern instead of ISO.DATE_TIME, which takes
    //an offset as well and silently drops it; an offset or a zone is a 400
    @GetMapping("history")
    Mono<WeatherHistoryReply> history(
        @RequestParam final String metric,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime from,
        @RequestParam @DateTimeFormat(pattern = LOCAL_DATE_TIME) final LocalDateTime to
    ) {
        return weatherHistoryService.queryHistory(metric, from, to)
            .doOnSubscribe(subscription -> log.info("Querying the weather history of {} from: {} to: {}", metric, from, to));
    }
}
