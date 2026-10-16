package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingMapperTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 9, 21, 28);

    //the values of the pairing (HAS-234), each into its column at the scale of V1
    @Test
    void should_put_every_metric_into_its_column() {
        final Map<Metric, Reading> readings = new EnumMap<>(Metric.class);
        readings.put(Metric.BATTERY, number("100"));
        readings.put(Metric.PRESSURE, number("993.3"));
        readings.put(Metric.DEW_POINT, number("4.11"));
        readings.put(Metric.VOLTAGE, number("4.2"));
        readings.put(Metric.HUMIDITY, number("98"));
        readings.put(Metric.TEMPERATURE, number("4.4"));
        readings.put(Metric.PRECIPITATION, number("4311"));
        readings.put(Metric.ILLUMINANCE, number("3310.0"));
        readings.put(Metric.RAIN, Reading.flag(true, AT));
        readings.put(Metric.WIND, number("1.1"));
        readings.put(Metric.GUST, number("1.7"));
        readings.put(Metric.UV, number("0.0"));
        readings.put(Metric.WIND_DIRECTION, number("173.0"));

        final WeatherReadingEntity entity = ReadingMapper.toEntity(new CurrentWeather.Snapshot(AT, readings));

        assertThat(entity).isEqualTo(new WeatherReadingEntity(null, AT, new BigDecimal("4.4"), 98, new BigDecimal("4.11"),
            new BigDecimal("993.30"), 3310, new BigDecimal("0.0"), new BigDecimal("1.10"), new BigDecimal("1.70"), 173,
            true, new BigDecimal("4311.0"), 100, new BigDecimal("4.20")));
    }

    @Test
    void should_round_a_whole_number_column_half_up() {
        final WeatherReadingEntity entity = ReadingMapper.toEntity(new CurrentWeather.Snapshot(AT, Map.of(
            Metric.HUMIDITY, number("98.5"), Metric.WIND_DIRECTION, number("163.49"))));

        assertThat(entity.humidity()).isEqualTo(99);
        assertThat(entity.windDirection()).isEqualTo(163);
    }

    @Test
    void should_leave_a_metric_never_received_null() {
        final WeatherReadingEntity entity = ReadingMapper.toEntity(new CurrentWeather.Snapshot(AT, Map.of(
            Metric.TEMPERATURE, number("4.4"))));

        assertThat(entity.temperature()).isEqualByComparingTo("4.4");
        assertThat(entity.raining()).isNull();
        assertThat(entity.humidity()).isNull();
        assertThat(entity.voltage()).isNull();
    }

    //back from a row every metric gets the time of the row
    @Test
    void should_read_a_row_back_into_the_state() {
        final WeatherReadingEntity entity = WeatherReadingEntity.builder()
            .id(7L).measuredAt(AT).temperature(new BigDecimal("4.4")).humidity(98).raining(false).build();

        assertThat(ReadingMapper.toReadings(entity)).containsExactlyInAnyOrderEntriesOf(Map.of(
            Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), AT),
            Metric.HUMIDITY, Reading.number(BigDecimal.valueOf(98), AT),
            Metric.RAIN, Reading.flag(false, AT)));
    }

    private static Reading number(final String value) {
        return Reading.number(new BigDecimal(value), AT);
    }
}
