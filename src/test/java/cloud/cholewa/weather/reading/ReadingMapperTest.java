package cloud.cholewa.weather.reading;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadingMapperTest {

    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Instant AT = Instant.parse("2026-10-16T07:21:28Z");
    private static final LocalDateTime LOCAL = LocalDateTime.of(2026, 10, 16, 9, 21, 28);

    private final ListAppender<ILoggingEvent> mapperLog = new ListAppender<>();

    @BeforeEach
    void setUp() {
        mapperLog.start();
        ((Logger) LoggerFactory.getLogger(ReadingMapper.class)).addAppender(mapperLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ReadingMapper.class)).detachAppender(mapperLog);
    }

    //the values of the pairing (HAS-234), each into its column at the scale of V1, at house time
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

        final WeatherReadingEntity entity = ReadingMapper.toEntity(new CurrentWeather.Snapshot(AT, readings), HOUSE);

        assertThat(entity).isEqualTo(new WeatherReadingEntity(null, LOCAL, new BigDecimal("4.4"), 98, new BigDecimal("4.11"),
            new BigDecimal("993.30"), 3310, new BigDecimal("0.0"), new BigDecimal("1.10"), new BigDecimal("1.70"), 173,
            true, new BigDecimal("4311.0"), 100, new BigDecimal("4.20")));
        assertThat(mapperLog.list).isEmpty();
    }

    @Test
    void should_round_a_whole_number_column_half_up() {
        final WeatherReadingEntity entity = ReadingMapper.toEntity(new CurrentWeather.Snapshot(AT, Map.of(
            Metric.HUMIDITY, number("98.5"), Metric.WIND_DIRECTION, number("163.49"))), HOUSE);

        assertThat(entity.humidity()).isEqualTo(99);
        assertThat(entity.windDirection()).isEqualTo(163);
    }

    @Test
    void should_leave_a_metric_never_received_null() {
        final WeatherReadingEntity entity = ReadingMapper.toEntity(new CurrentWeather.Snapshot(AT, Map.of(
            Metric.TEMPERATURE, number("4.4"))), HOUSE);

        assertThat(entity.temperature()).isEqualByComparingTo("4.4");
        assertThat(entity.raining()).isNull();
        assertThat(entity.humidity()).isNull();
        assertThat(entity.voltage()).isNull();
    }

    //PostgreSQL would refuse the whole row - and every row after it until the metric changed
    @Test
    void should_store_a_value_that_does_not_fit_its_column_as_null() {
        final WeatherReadingEntity entity = ReadingMapper.toEntity(new CurrentWeather.Snapshot(AT, Map.of(
            Metric.UV, number("100.0"),
            Metric.HUMIDITY, number("70000"),
            Metric.TEMPERATURE, number("-999.9"),
            Metric.PRESSURE, number("993.3"))), HOUSE);

        assertThat(entity.uv()).isNull();
        assertThat(entity.humidity()).isNull();
        assertThat(entity.temperature()).isEqualByComparingTo("-999.9");
        assertThat(entity.pressure()).isEqualByComparingTo("993.3");
        assertThat(mapperLog.list).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    //back from a row every metric gets the time of the row, as an instant
    @Test
    void should_read_a_row_back_into_the_state() {
        final WeatherReadingEntity entity = WeatherReadingEntity.builder()
            .id(7L).measuredAt(LOCAL).temperature(new BigDecimal("4.4")).humidity(98).raining(false).build();

        assertThat(ReadingMapper.toReadings(entity, HOUSE)).containsExactlyInAnyOrderEntriesOf(Map.of(
            Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), AT),
            Metric.HUMIDITY, Reading.number(BigDecimal.valueOf(98), AT),
            Metric.RAIN, Reading.flag(false, AT)));
    }

    private static Reading number(final String value) {
        return Reading.number(new BigDecimal(value), AT);
    }
}
