package cloud.cholewa.weather.air;

import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

//The readings of one answer of the air sensor (HAS-427). The device has no clock of its own: it gives the
//seconds since its last valid measurement, so a reading is measured at the instant of the pod minus those
//seconds, to the second. A sensor in error (state 3), without a measurement (elapsedTimeS -1) or without
//a value is no reading. The level of the air is measured when the newest of the particle readings was;
//without any of them, or below 1 (the GIOS scale runs 1 - 6), it is none either
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AirReadings {

    static final int STATE_ERROR = 3;
    static final int LOWEST_LEVEL = 1;

    public static Map<Metric, Reading> of(final AirStateResponse response, final Instant now) {
        final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
        final AirStateResponse.Air air = response.air();
        final List<AirStateResponse.Sensor> sensors = air.sensors() == null ? List.of() : air.sensors();
        sensors.stream()
            .filter(Objects::nonNull)
            .forEach(sensor -> metricOf(sensor.type())
                .flatMap(metric -> readingOf(sensor, now).map(reading -> Map.entry(metric, reading)))
                .ifPresent(entry -> readings.putIfAbsent(entry.getKey(), entry.getValue())));
        final Integer level = air.airQualityLevel();
        readings.values().stream()
            .map(Reading::measuredAt)
            .max(Instant::compareTo)
            .filter(at -> level != null && level >= LOWEST_LEVEL)
            .ifPresent(at -> readings.put(Metric.AIR_QUALITY_LEVEL, Reading.number(BigDecimal.valueOf(level), at)));
        return readings;
    }

    private static Optional<Metric> metricOf(final String type) {
        return switch (type == null ? "" : type) {
            case "pm1" -> Optional.of(Metric.PM1);
            case "pm2.5" -> Optional.of(Metric.PM25);
            case "pm10" -> Optional.of(Metric.PM10);
            default -> Optional.empty();
        };
    }

    private static Optional<Reading> readingOf(final AirStateResponse.Sensor sensor, final Instant now) {
        final Long elapsed = sensor.elapsedTimeS();
        if (sensor.value() == null || elapsed == null || elapsed < 0
            || sensor.state() != null && sensor.state() == STATE_ERROR) {
            return Optional.empty();
        }
        return Optional.of(Reading.number(BigDecimal.valueOf(sensor.value()),
            now.minusSeconds(elapsed).truncatedTo(ChronoUnit.SECONDS)));
    }
}
