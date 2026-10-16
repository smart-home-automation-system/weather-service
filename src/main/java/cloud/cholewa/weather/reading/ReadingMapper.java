package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

//A snapshot of the state to a row and a row back to the state. The scale of each column is the one
//the gateway reports (V1); a whole-number column - humidity, illuminance, the wind direction, the
//battery - is rounded half up, so 98.6 % is stored as 99. Back from a row every metric gets the
//measured_at of the row: the time of each metric is not stored
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ReadingMapper {

    static WeatherReadingEntity toEntity(final CurrentWeather.Snapshot snapshot) {
        final Map<Metric, Reading> readings = snapshot.readings();
        return WeatherReadingEntity.builder()
            .measuredAt(snapshot.measuredAt())
            .temperature(number(readings, Metric.TEMPERATURE, 1))
            .humidity(whole(readings, Metric.HUMIDITY))
            .dewPoint(number(readings, Metric.DEW_POINT, 2))
            .pressure(number(readings, Metric.PRESSURE, 2))
            .illuminance(whole(readings, Metric.ILLUMINANCE))
            .uv(number(readings, Metric.UV, 1))
            .wind(number(readings, Metric.WIND, 2))
            .gust(number(readings, Metric.GUST, 2))
            .windDirection(whole(readings, Metric.WIND_DIRECTION))
            .raining(readings.containsKey(Metric.RAIN) ? readings.get(Metric.RAIN).flag() : null)
            .precipitation(number(readings, Metric.PRECIPITATION, 1))
            .battery(whole(readings, Metric.BATTERY))
            .voltage(number(readings, Metric.VOLTAGE, 2))
            .build();
    }

    static Map<Metric, Reading> toReadings(final WeatherReadingEntity entity) {
        final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
        final LocalDateTime at = entity.measuredAt();
        put(readings, Metric.TEMPERATURE, entity.temperature(), at);
        put(readings, Metric.HUMIDITY, entity.humidity(), at);
        put(readings, Metric.DEW_POINT, entity.dewPoint(), at);
        put(readings, Metric.PRESSURE, entity.pressure(), at);
        put(readings, Metric.ILLUMINANCE, entity.illuminance(), at);
        put(readings, Metric.UV, entity.uv(), at);
        put(readings, Metric.WIND, entity.wind(), at);
        put(readings, Metric.GUST, entity.gust(), at);
        put(readings, Metric.WIND_DIRECTION, entity.windDirection(), at);
        put(readings, Metric.PRECIPITATION, entity.precipitation(), at);
        put(readings, Metric.BATTERY, entity.battery(), at);
        put(readings, Metric.VOLTAGE, entity.voltage(), at);
        if (entity.raining() != null) {
            readings.put(Metric.RAIN, Reading.flag(entity.raining(), at));
        }
        return readings;
    }

    private static BigDecimal number(final Map<Metric, Reading> readings, final Metric metric, final int scale) {
        return value(readings, metric, number -> number.setScale(scale, RoundingMode.HALF_UP));
    }

    private static Integer whole(final Map<Metric, Reading> readings, final Metric metric) {
        return value(readings, metric, number -> number.setScale(0, RoundingMode.HALF_UP).intValueExact());
    }

    private static <T> T value(final Map<Metric, Reading> readings, final Metric metric, final Function<BigDecimal, T> as) {
        final Reading reading = readings.get(metric);
        return reading == null || reading.number() == null ? null : as.apply(reading.number());
    }

    private static void put(final Map<Metric, Reading> readings, final Metric metric, final BigDecimal value,
                            final LocalDateTime at) {
        if (value != null) {
            readings.put(metric, Reading.number(value, at));
        }
    }

    private static void put(final Map<Metric, Reading> readings, final Metric metric, final Integer value,
                            final LocalDateTime at) {
        if (value != null) {
            readings.put(metric, Reading.number(BigDecimal.valueOf(value), at));
        }
    }
}
