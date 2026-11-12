package cloud.cholewa.weather.air;

import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AirReadingsTest {

    private static final Instant NOW = Instant.parse("2026-11-12T13:00:00.600Z");

    private static AirStateResponse.Sensor sensor(final String type, final Integer value, final Integer state, final Long elapsed) {
        return new AirStateResponse.Sensor(type, value, 3, state, 1, elapsed);
    }

    private static AirStateResponse answer(final Integer level, final AirStateResponse.Sensor... sensors) {
        return new AirStateResponse(new AirStateResponse.Air(level, List.of(sensors)));
    }

    //the answer of the sensor of the house: measured 374 s before the read, to the second
    @Test
    void should_read_every_particle_size_and_the_level_measured_before_the_read() {
        final Instant measured = Instant.parse("2026-11-12T12:53:46Z");

        assertThat(AirReadings.of(answer(1,
            sensor("pm1", 7, 0, 374L), sensor("pm2.5", 12, 0, 374L), sensor("pm10", 12, 0, 374L)), NOW))
            .containsExactly(
                java.util.Map.entry(Metric.PM1, Reading.number(new BigDecimal("7"), measured)),
                java.util.Map.entry(Metric.PM25, Reading.number(new BigDecimal("12"), measured)),
                java.util.Map.entry(Metric.PM10, Reading.number(new BigDecimal("12"), measured)),
                java.util.Map.entry(Metric.AIR_QUALITY_LEVEL, Reading.number(new BigDecimal("1"), measured)));
    }

    //a sensor in error, without a measurement or without a value is no reading - never a zero
    @Test
    void should_take_no_reading_of_a_sensor_in_error_or_without_a_measurement() {
        assertThat(AirReadings.of(answer(2,
            sensor("pm1", 7, 3, 10L), sensor("pm2.5", 12, 0, -1L), sensor("pm10", null, 0, 10L)), NOW)).isEmpty();
        assertThat(AirReadings.of(answer(2, sensor("pm10", 12, 0, null)), NOW)).isEmpty();
    }

    //the level is measured when the newest particle reading was; below the scale it is none
    @Test
    void should_date_the_level_by_the_newest_reading_and_take_none_below_the_scale() {
        assertThat(AirReadings.of(answer(3, sensor("pm1", 7, 0, 600L), sensor("pm10", 40, 0, 60L)), NOW))
            .containsEntry(Metric.AIR_QUALITY_LEVEL, Reading.number(new BigDecimal("3"), Instant.parse("2026-11-12T12:59:00Z")));
        assertThat(AirReadings.of(answer(-1, sensor("pm10", 40, 0, 60L)), NOW)).containsOnlyKeys(Metric.PM10);
        assertThat(AirReadings.of(answer(null, sensor("pm10", 40, 0, 60L)), NOW)).containsOnlyKeys(Metric.PM10);
    }

    @Test
    void should_ignore_a_type_it_does_not_know_and_a_list_that_is_not_there() {
        assertThat(AirReadings.of(answer(1, sensor("pm4", 9, 0, 10L)), NOW)).isEmpty();
        assertThat(AirReadings.of(new AirStateResponse(new AirStateResponse.Air(1, null)), NOW)).isEmpty();
    }
}
