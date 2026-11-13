package cloud.cholewa.weather.current;

import cloud.cholewa.weather.air.AirQuality;
import cloud.cholewa.weather.air.AirSensorWatch;
import cloud.cholewa.weather.air.LaserRuntime;
import cloud.cholewa.weather.air.SmogWatch;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

//What the read API answers from memory: the current state and how the station is heard - and the air
//quality sensor (HAS-427), whose readings stand beside those of the station. Old is
//"older than weather.monitor.silent-after" - the threshold of the alerts, so the dashboard shows as
//stale what the household is told about. Times go out as house time without an offset, to the second
@Service
@RequiredArgsConstructor
public class CurrentWeatherQuery {

    private final CurrentWeather currentWeather;
    private final WeatherStation weatherStation;
    private final AirQuality airQuality;
    private final AirSensorWatch airSensorWatch;
    private final SmogWatch smogWatch;
    private final LaserRuntime laserRuntime;
    private final MonitorProperties monitorProperties;
    private final Clock clock;

    //per metric of the read API, in the order of Metric; a metric never received is left out
    public Map<String, CurrentMetric> current() {
        final Instant now = clock.instant();
        final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
        readings.putAll(currentWeather.confirmedReadings());
        readings.putAll(airQuality.confirmedReadings());
        final Map<String, CurrentMetric> current = new LinkedHashMap<>();
        readings.forEach((metric, reading) ->
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
        return new StationStatus(gateway, heardAt == null ? null : houseTime(heardAt), heardAt == null || isOld(heardAt, now),
            airSensor());
    }

    private StationStatus.AirSensor airSensor() {
        return airQuality.sensor()
            .map(sensor -> {
                final AirQuality.Check check = airQuality.lastCheck().orElse(null);
                final LocalDateTime lastReadingAt = airQuality.lastReadingAt().map(this::houseTime).orElse(null);
                final boolean silent = airSensorWatch.silent();
                final StationStatus.Smog smog = smogWatch.openEpisode()
                    .map(episode -> new StationStatus.Smog(houseTime(episode.since()), episode.level()))
                    .orElse(null);
                final StationStatus.Laser laser = laserRuntime.laser()
                    .map(known -> new StationStatus.Laser(known.hours(), houseTime(known.readAt()), known.lifeHours(), known.warn()))
                    .orElse(null);
                return check == null
                    ? new StationStatus.AirSensor(sensor.address(), null, null, lastReadingAt, silent, smog, laser)
                    : new StationStatus.AirSensor(sensor.address(), check.answered(), houseTime(check.checkedAt()), lastReadingAt,
                        silent, smog, laser);
            })
            .orElse(null);
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
