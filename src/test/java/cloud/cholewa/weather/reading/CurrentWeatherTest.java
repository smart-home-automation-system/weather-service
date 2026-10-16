package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.StationLink;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentWeatherTest {

    private static final Instant AT = Instant.parse("2026-10-16T07:21:28Z");

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

    //the last Sunday of October house time goes from 02:59 CEST back to 02:00 CET: a reading after it
    //is newer, whatever its local time says
    @Test
    void should_take_a_reading_after_the_clocks_go_back() {
        final Instant beforeTheChange = Instant.parse("2026-10-25T00:58:00Z");
        final Instant afterTheChange = Instant.parse("2026-10-25T01:01:00Z");
        assertThat(LocalDateTime.ofInstant(afterTheChange, java.time.ZoneId.of("Europe/Warsaw")))
            .isBefore(LocalDateTime.ofInstant(beforeTheChange, java.time.ZoneId.of("Europe/Warsaw")));
        sut.observe(Metric.TEMPERATURE, number("4.4", beforeTheChange));

        assertThat(sut.observe(Metric.TEMPERATURE, number("4.2", afterTheChange))).isTrue();
    }

    @Test
    void should_take_a_snapshot_only_when_something_changed_since_the_last() {
        assertThat(sut.takeSnapshot()).isEmpty();

        sut.observe(Metric.PRESSURE, number("993.3", AT));
        sut.observe(Metric.RAIN, Reading.flag(true, AT.plusSeconds(2)));

        assertThat(sut.takeSnapshot()).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.measuredAt()).isEqualTo(AT.plusSeconds(2));
            assertThat(snapshot.readings()).containsOnlyKeys(Metric.PRESSURE, Metric.RAIN);
        });
        assertThat(sut.takeSnapshot()).isEmpty();
    }

    //a status the gateway repeats, or a redelivery, is no change - and no row
    @Test
    void should_take_no_snapshot_for_the_same_reading_again() {
        sut.observe(Metric.PRESSURE, number("993.30", AT));
        sut.takeSnapshot();

        assertThat(sut.observe(Metric.PRESSURE, number("993.3", AT))).isFalse();
        assertThat(sut.takeSnapshot()).isEmpty();
    }

    //a read of the map: what is not held yet is taken - and is a change
    @Test
    void should_seed_a_metric_not_held_yet() {
        assertThat(sut.seed(Metric.BATTERY, number("100", AT))).isTrue();
        assertThat(sut.takeSnapshot()).isPresent();
    }

    //the gateway may move the time of a sensor without publishing it: the same value later is no
    //change, and no row
    @Test
    void should_not_seed_the_same_value_at_a_later_time() {
        sut.observe(Metric.BATTERY, number("100", AT));
        sut.takeSnapshot();

        assertThat(sut.seed(Metric.BATTERY, number("100", AT.plusSeconds(3600)))).isFalse();
        assertThat(sut.takeSnapshot()).isEmpty();
        assertThat(sut.readings()).containsEntry(Metric.BATTERY, number("100", AT));
    }

    //the map may have been answered before an event of the same second: the event stays
    @Test
    void should_never_seed_over_a_reading_of_the_same_second_or_older() {
        sut.observe(Metric.WIND, number("1.4", AT));

        assertThat(sut.seed(Metric.WIND, number("1.1", AT))).isFalse();
        assertThat(sut.seed(Metric.WIND, number("1.0", AT.minusSeconds(9)))).isFalse();
        assertThat(sut.readings()).containsEntry(Metric.WIND, number("1.4", AT));
    }

    @Test
    void should_seed_another_value_measured_later() {
        sut.observe(Metric.WIND, number("1.4", AT));

        assertThat(sut.seed(Metric.WIND, number("1.1", AT.plusSeconds(9)))).isTrue();
    }

    @Test
    void should_take_the_snapshot_again_after_it_failed() {
        sut.observe(Metric.PRESSURE, number("993.3", AT));
        sut.takeSnapshot();

        sut.snapshotFailed();

        assertThat(sut.takeSnapshot()).isPresent();
    }

    //the restored state is no change, and never replaces a reading that arrived
    @Test
    void should_restore_only_what_no_message_brought() {
        sut.observe(Metric.TEMPERATURE, number("4.4", AT));
        sut.takeSnapshot();

        sut.restore(Map.of(
            Metric.TEMPERATURE, number("3.0", AT.minusSeconds(300)),
            Metric.WIND_DIRECTION, number("173", AT.minusSeconds(300))));

        assertThat(sut.readings()).containsExactlyInAnyOrderEntriesOf(Map.of(
            Metric.TEMPERATURE, number("4.4", AT),
            Metric.WIND_DIRECTION, number("173", AT.minusSeconds(300))));
        assertThat(sut.takeSnapshot()).isEmpty();
    }

    @Test
    void should_forget_everything_when_cleared() {
        sut.observe(Metric.TEMPERATURE, number("4.4", AT));
        sut.link(new StationLink(-73, 100, 129, LocalDateTime.of(2026, 10, 16, 9, 21)));

        sut.clear();

        assertThat(sut.readings()).isEmpty();
        assertThat(sut.link()).isEmpty();
        assertThat(sut.takeSnapshot()).isEmpty();
    }

    //what the watch over the station judges by: every reading counts, taken or not
    @Test
    void should_know_when_the_station_was_heard_last() {
        assertThat(sut.heardAt()).isEmpty();
        sut.observe(Metric.BATTERY, number("100", AT));

        sut.seed(Metric.BATTERY, number("100", AT.plusSeconds(600)));
        sut.observe(Metric.WIND, number("1.1", AT.plusSeconds(60)));

        assertThat(sut.heardAt()).contains(AT.plusSeconds(600));
    }

    @Test
    void should_know_the_station_heard_from_the_restored_row() {
        sut.restore(Map.of(Metric.TEMPERATURE, number("4.4", AT)));

        assertThat(sut.heardAt()).contains(AT);
    }

    //another station: what the last one did is no sign of life of this one
    @Test
    void should_forget_when_the_station_was_heard_when_cleared() {
        sut.heard(AT);

        sut.clear();

        assertThat(sut.heardAt()).isEmpty();
    }

    private static Reading number(final String value, final Instant at) {
        return Reading.number(new BigDecimal(value), at);
    }
}
