package cloud.cholewa.weather.current;

import cloud.cholewa.weather.api.model.CurrentMetric;
import cloud.cholewa.weather.api.model.StationStatus;
import cloud.cholewa.weather.config.MonitorProperties;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.reading.CurrentWeather;
import cloud.cholewa.weather.station.WeatherStation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

//What the read API answers from memory: the current state and how the station is heard. Old is
//"older than weather.monitor.silent-after" - the threshold of the alerts, so the dashboard shows as
//stale what the household is told about. Times go out as house time without an offset, to the second
@Service
@RequiredArgsConstructor
public class CurrentWeatherQuery {

    private final CurrentWeather currentWeather;
    private final WeatherStation weatherStation;
    private final MonitorProperties monitorProperties;
    private final Clock clock;

    //per metric of the read API, in the order of Metric; a metric never received is left out
    public Map<String, CurrentMetric> current() {
        final Instant now = clock.instant();
        final Map<String, CurrentMetric> current = new LinkedHashMap<>();
        currentWeather.confirmedReadings().forEach((metric, reading) ->
            current.put(metric.apiName(), new CurrentMetric(value(metric, reading), houseTime(reading.measuredAt()),
                isOld(reading.measuredAt(), now))));
        return current;
    }

    public StationStatus status() {
        final Instant now = clock.instant();
        final StationStatus.Gateway gateway = weatherStation.station()
            .map(station -> weatherStation.lastCheck()
                .map(check -> new StationStatus.Gateway(station.gatewayDeviceId(), check.answered(), houseTime(check.checkedAt())))
                .orElseGet(() -> new StationStatus.Gateway(station.gatewayDeviceId(), null, null)))
            .orElse(null);
        final Instant heardAt = currentWeather.heardAt().orElse(null);
        return new StationStatus(gateway, heardAt == null ? null : houseTime(heardAt), heardAt == null || isOld(heardAt, now));
    }

    private static Object value(final Metric metric, final Reading reading) {
        return metric.isFlag() ? reading.flag() : reading.number();
    }

    private boolean isOld(final Instant at, final Instant now) {
        return !at.plus(monitorProperties.silentAfter()).isAfter(now);
    }

    private LocalDateTime houseTime(final Instant at) {
        return LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.SECONDS), clock.getZone());
    }
}
