package cloud.cholewa.weather.current;

import cloud.cholewa.weather.api.model.CurrentMetric;
import cloud.cholewa.weather.api.model.StationStatus;
import cloud.cholewa.weather.config.MonitorProperties;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.reading.CurrentWeather;
import cloud.cholewa.weather.station.WeatherStation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentWeatherQueryTest {

    //14:00 house time
    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00.750Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Warsaw"));
    private static final Station STATION = new Station(55, "WS90", "08:b9:5f:d1:e6:71", 54,
        "shellyplugsg3-28372f2f0460", "10.78.30.36");

    private final CurrentWeather currentWeather = new CurrentWeather(CLOCK);
    private final WeatherStation weatherStation = mock(WeatherStation.class);
    private final CurrentWeatherQuery sut = new CurrentWeatherQuery(currentWeather, weatherStation,
        new MonitorProperties(Duration.ofMinutes(30), Duration.ofHours(1)), CLOCK);

    @BeforeEach
    void setUp() {
        when(weatherStation.station()).thenReturn(Optional.of(STATION));
        when(weatherStation.lastCheck()).thenReturn(Optional.empty());
    }

    @Test
    void should_answer_each_metric_held_by_its_name_with_house_time() {
        currentWeather.observe(Metric.DEW_POINT, Reading.number(new BigDecimal("8.12"), NOW.minusSeconds(40)));
        currentWeather.observe(Metric.RAIN, Reading.flag(false, NOW.minusSeconds(40)));

        assertThat(sut.current()).containsOnlyKeys("dewPoint", "raining")
            .containsEntry("dewPoint", new CurrentMetric(new BigDecimal("8.12"), LocalDateTime.of(2026, 10, 16, 13, 59, 20), false))
            .containsEntry("raining", new CurrentMetric(false, LocalDateTime.of(2026, 10, 16, 13, 59, 20), false));
    }

    //a metric never received is not there at all
    @Test
    void should_answer_nothing_before_the_first_reading() {
        assertThat(sut.current()).isEmpty();
    }

    @Test
    void should_mark_a_value_older_than_the_alert_threshold_as_stale() {
        currentWeather.observe(Metric.UV, Reading.number(new BigDecimal("0.0"), NOW.minus(Duration.ofMinutes(30))));
        currentWeather.observe(Metric.WIND, Reading.number(new BigDecimal("1.1"), NOW.minus(Duration.ofMinutes(29))));

        assertThat(sut.current().get("uv").stale()).isTrue();
        assertThat(sut.current().get("wind").stale()).isFalse();
    }

    //the battery stays at 100 for weeks: every read of the gateway confirms it, so it is not stale
    @Test
    void should_judge_a_value_that_does_not_change_by_its_last_confirmation() {
        currentWeather.observe(Metric.BATTERY, Reading.number(new BigDecimal("100"), NOW.minus(Duration.ofDays(3))));
        currentWeather.seed(Metric.BATTERY, Reading.number(new BigDecimal("100"), NOW.minus(Duration.ofMinutes(4))));

        assertThat(sut.current().get("battery"))
            .isEqualTo(new CurrentMetric(new BigDecimal("100"), LocalDateTime.of(2026, 10, 16, 13, 56, 0), false));
    }

    @Test
    void should_answer_the_status_of_a_station_heard_and_a_gateway_that_answered() {
        currentWeather.heard(NOW.minusSeconds(20));
        when(weatherStation.lastCheck()).thenReturn(Optional.of(new WeatherStation.GatewayCheck(STATION, NOW.minusSeconds(65), true)));

        assertThat(sut.status()).isEqualTo(new StationStatus(
            new StationStatus.Gateway("shellyplugsg3-28372f2f0460", true, LocalDateTime.of(2026, 10, 16, 13, 58, 55)),
            LocalDateTime.of(2026, 10, 16, 13, 59, 40), false));
    }

    @Test
    void should_answer_a_gateway_that_did_not_answer_and_a_silent_station() {
        currentWeather.heard(NOW.minus(Duration.ofMinutes(31)));
        when(weatherStation.lastCheck()).thenReturn(Optional.of(new WeatherStation.GatewayCheck(STATION, NOW, false)));

        final StationStatus status = sut.status();

        assertThat(status.gateway().reachable()).isFalse();
        assertThat(status.silent()).isTrue();
    }

    //missing is never off: before anything is known the fields are left out, and the station is silent
    @Test
    void should_leave_out_what_is_not_known_yet() {
        assertThat(sut.status()).isEqualTo(new StationStatus(
            new StationStatus.Gateway("shellyplugsg3-28372f2f0460", null, null), null, true));

        when(weatherStation.station()).thenReturn(Optional.empty());
        assertThat(sut.status()).isEqualTo(new StationStatus(null, null, true));
    }
}
