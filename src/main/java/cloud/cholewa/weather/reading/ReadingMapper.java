package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

//A snapshot of the state to a row and a row back to the state. The scale of each column is the one
//the gateway reports (V1); a whole-number column - humidity, illuminance, the wind direction, the
//battery - is rounded half up, so 98.6 % is stored as 99. A value that does not fit its column is
//stored as null and said at WARN: PostgreSQL would refuse the whole row, and the same value would
//refuse every row after it until the metric changed. measured_at is house time; back from a row every
//metric gets the measured_at of the row (the time of each metric is not stored) - on the hour the
//clocks go back the earlier of the two instants
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ReadingMapper {

    //the largest absolute value each column holds: NUMERIC(p, s) below 10^(p-s), SMALLINT, INTEGER
    private static final BigDecimal SMALLINT = BigDecimal.valueOf(Short.MAX_VALUE);
    private static final BigDecimal INTEGER = BigDecimal.valueOf(Integer.MAX_VALUE);

    static WeatherReadingEntity toEntity(final CurrentWeather.Snapshot snapshot, final ZoneId zone) {
        final Map<Metric, Reading> readings = snapshot.readings();
        return WeatherReadingEntity.builder()
            .measuredAt(LocalDateTime.ofInstant(snapshot.measuredAt(), zone))
            .temperature(number(readings, Metric.TEMPERATURE, 4, 1))
            .humidity(whole(readings, Metric.HUMIDITY, SMALLINT))
            .dewPoint(number(readings, Metric.DEW_POINT, 5, 2))
            .pressure(number(readings, Metric.PRESSURE, 6, 2))
            .illuminance(whole(readings, Metric.ILLUMINANCE, INTEGER))
            .uv(number(readings, Metric.UV, 3, 1))
            .wind(number(readings, Metric.WIND, 5, 2))
            .gust(number(readings, Metric.GUST, 5, 2))
            .windDirection(whole(readings, Metric.WIND_DIRECTION, SMALLINT))
            .raining(readings.containsKey(Metric.RAIN) ? readings.get(Metric.RAIN).flag() : null)
            .precipitation(number(readings, Metric.PRECIPITATION, 6, 1))
            .battery(whole(readings, Metric.BATTERY, SMALLINT))
            .voltage(number(readings, Metric.VOLTAGE, 4, 2))
            .build();
    }

    static Map<Metric, Reading> toReadings(final WeatherReadingEntity entity, final ZoneId zone) {
        final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
        final Instant at = entity.measuredAt().atZone(zone).toInstant();
        put(readings, Metric.TEMPERATURE, entity.temperature(), at);
        put(readings, Metric.HUMIDITY, whole(entity.humidity()), at);
        put(readings, Metric.DEW_POINT, entity.dewPoint(), at);
        put(readings, Metric.PRESSURE, entity.pressure(), at);
        put(readings, Metric.ILLUMINANCE, whole(entity.illuminance()), at);
        put(readings, Metric.UV, entity.uv(), at);
        put(readings, Metric.WIND, entity.wind(), at);
        put(readings, Metric.GUST, entity.gust(), at);
        put(readings, Metric.WIND_DIRECTION, whole(entity.windDirection()), at);
        put(readings, Metric.PRECIPITATION, entity.precipitation(), at);
        put(readings, Metric.BATTERY, whole(entity.battery()), at);
        put(readings, Metric.VOLTAGE, entity.voltage(), at);
        if (entity.raining() != null) {
            readings.put(Metric.RAIN, Reading.flag(entity.raining(), at));
        }
        return readings;
    }

    private static BigDecimal number(final Map<Metric, Reading> readings, final Metric metric,
                                     final int precision, final int scale) {
        final BigDecimal value = value(readings, metric);
        if (value == null) {
            return null;
        }
        final BigDecimal scaled = value.setScale(scale, RoundingMode.HALF_UP);
        return fits(metric, value, scaled.precision() - scaled.scale() <= precision - scale) ? scaled : null;
    }

    private static Integer whole(final Map<Metric, Reading> readings, final Metric metric, final BigDecimal limit) {
        final BigDecimal value = value(readings, metric);
        if (value == null) {
            return null;
        }
        final BigDecimal rounded = value.setScale(0, RoundingMode.HALF_UP);
        return fits(metric, value, rounded.abs().compareTo(limit) <= 0) ? rounded.intValueExact() : null;
    }

    private static boolean fits(final Metric metric, final BigDecimal value, final boolean fits) {
        if (!fits) {
            log.warn("The value {} of {} does not fit its column and is stored as null", value, metric);
        }
        return fits;
    }

    private static BigDecimal value(final Map<Metric, Reading> readings, final Metric metric) {
        final Reading reading = readings.get(metric);
        return reading == null ? null : reading.number();
    }

    private static BigDecimal whole(final Integer value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static void put(final Map<Metric, Reading> readings, final Metric metric, final BigDecimal value,
                            final Instant at) {
        if (value != null) {
            readings.put(metric, Reading.number(value, at));
        }
    }
}
