package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentWeatherTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 9, 21, 28);

    private final CurrentWeather sut = new CurrentWeather();

    @Test
    void should_hold_the_last_reading_of_each_metric() {
        sut.observe(Metric.TEMPERATURE, number("4.4", AT));
        sut.observe(Metric.TEMPERATURE, number("4.5", AT.plusSeconds(19)));
        sut.observe(Metric.HUMIDITY, number("98", AT));

        assertThat(sut.readings()).containsExactlyInAnyOrderEntriesOf(Map.of(
            Metric.TEMPERATURE, number("4.5", AT.plusSeconds(19)),
            Metric.HUMIDITY, number("98", AT)));
    }

    //a redelivered message must not take the state back
    @Test
    void should_drop_a_reading_older_than_the_one_held() {
        sut.observe(Metric.WIND, number("1.4", AT.plusSeconds(9)));

        assertThat(sut.observe(Metric.WIND, number("1.1", AT))).isFalse();
        assertThat(sut.readings()).containsEntry(Metric.WIND, number("1.4", AT.plusSeconds(9)));
    }

    @Test
    void should_take_a_snapshot_only_when_a_reading_arrived_since_the_last() {
        assertThat(sut.takeSnapshot()).isEmpty();

        sut.observe(Metric.PRESSURE, number("993.3", AT));
        sut.observe(Metric.RAIN, Reading.flag(true, AT.plusSeconds(2)));

        assertThat(sut.takeSnapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.measuredAt()).isEqualTo(AT.plusSeconds(2));
            assertThat(snapshot.readings()).containsOnlyKeys(Metric.PRESSURE, Metric.RAIN);
        });
        assertThat(sut.takeSnapshot()).isEmpty();
    }

    @Test
    void should_take_the_snapshot_again_after_it_failed() {
        sut.observe(Metric.PRESSURE, number("993.3", AT));
        sut.takeSnapshot();

        sut.snapshotFailed();

        assertThat(sut.takeSnapshot()).isPresent();
    }

    //the restored state is no reading that arrived, and never replaces one that did
    @Test
    void should_restore_only_what_no_message_brought() {
        sut.observe(Metric.TEMPERATURE, number("4.4", AT));
        sut.takeSnapshot();

        sut.restore(Map.of(
            Metric.TEMPERATURE, number("3.0", AT.minusMinutes(5)),
            Metric.WIND_DIRECTION, number("173", AT.minusMinutes(5))));

        assertThat(sut.readings()).containsExactlyInAnyOrderEntriesOf(Map.of(
            Metric.TEMPERATURE, number("4.4", AT),
            Metric.WIND_DIRECTION, number("173", AT.minusMinutes(5))));
        assertThat(sut.takeSnapshot()).isEmpty();
    }

    private static Reading number(final String value, final LocalDateTime at) {
        return Reading.number(new BigDecimal(value), at);
    }
}
