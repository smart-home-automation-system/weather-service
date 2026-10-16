package cloud.cholewa.weather.reading;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.shelly.codec.ShellyDecodeException;
import cloud.cholewa.shelly.codec.ShellyMqttDecoder;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.model.StationLink;
import cloud.cholewa.weather.model.StationSensors;
import cloud.cholewa.weather.station.WeatherStation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//the bodies are the ones the gateway TREADMILL published (HAS-234)
class WeatherEventRouterTest {

    private static final String GATEWAY = "shellyplugsg3-28372f2f0460";
    private static final ZoneId HOUSE = ZoneId.of("Europe/Warsaw");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T07:30:00Z"), HOUSE);
    //1792135288 = 2026-10-16T07:21:28Z
    private static final Instant MEASURED = Instant.parse("2026-10-16T07:21:28Z");

    private final WeatherStation weatherStation = mock(WeatherStation.class);
    private final CurrentWeather currentWeather = new CurrentWeather();
    private final ListAppender<ILoggingEvent> routerLog = new ListAppender<>();
    private final WeatherEventRouter sut = new WeatherEventRouter(weatherStation, currentWeather,
        new ShellyMqttDecoder(JsonMapper.builder().build()), CLOCK);

    @BeforeEach
    void setUp() {
        when(weatherStation.station()).thenReturn(Optional.of(
            new Station(55, "Weather station", "08:b9:5f:d1:e6:71", 54, GATEWAY, "10.78.30.36")));
        when(weatherStation.sensors()).thenReturn(Optional.of(new StationSensors(Map.of(
            205, Metric.TEMPERATURE, 208, Metric.RAIN, 212, Metric.WIND_DIRECTION), Set.of(240, 241), 200)));
        routerLog.start();
        ((Logger) LoggerFactory.getLogger(WeatherEventRouter.class)).addAppender(routerLog);
        ((Logger) LoggerFactory.getLogger(SensorReadings.class)).addAppender(routerLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(WeatherEventRouter.class)).detachAppender(routerLog);
        ((Logger) LoggerFactory.getLogger(SensorReadings.class)).detachAppender(routerLog);
    }

    @Test
    void should_take_a_number_of_a_sensor_of_the_station() {
        route(GATEWAY + ".status.bthomesensor:205", "{\"id\":205,\"value\":4.4,\"last_updated_ts\":1792135288}");

        assertThat(currentWeather.readings())
            .containsExactlyEntriesOf(Map.of(Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), MEASURED)));
    }

    @Test
    void should_take_the_flag_of_the_rain() {
        route(GATEWAY + ".status.bthomesensor:208", "{\"id\":208,\"value\":true,\"last_updated_ts\":1792135288}");

        assertThat(currentWeather.readings()).containsExactlyEntriesOf(Map.of(Metric.RAIN, Reading.flag(true, MEASURED)));
    }

    //the slash form of the topic and the case of the id: one copied from the page of the device is upper case
    @Test
    void should_read_the_topic_in_either_form_and_case() {
        route(GATEWAY.toUpperCase() + "/status/bthomesensor:212", "{\"id\":212,\"value\":173.00,\"last_updated_ts\":1792135288}");

        assertThat(currentWeather.readings()).containsKey(Metric.WIND_DIRECTION);
    }

    @Test
    void should_take_the_link_of_the_station() {
        route(GATEWAY + ".status.bthomedevice:200",
            "{\"id\":200,\"rssi\":-73,\"battery\":100,\"packet_id\":129,\"last_updated_ts\":1792135314,\"key\":false}");

        assertThat(currentWeather.link()).contains(new StationLink(-73, 100, 129, LocalDateTime.now(CLOCK)));
    }

    //DoD of HAS-236: an unknown sensor id is a WARN and ignored, never a failed message - said once,
    //and the map is read again
    @Test
    void should_ignore_a_sensor_the_map_does_not_have() {
        route(GATEWAY + ".status.bthomesensor:230", "{\"id\":230,\"value\":21.5,\"last_updated_ts\":1792135288}");
        route(GATEWAY + ".status.bthomesensor:230", "{\"id\":230,\"value\":21.6,\"last_updated_ts\":1792135298}");

        assertThat(currentWeather.readings()).isEmpty();
        assertThat(routerLog.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("bthomesensor:230");
        });
        verify(weatherStation).requestSensors();
    }

    //the sensors of the other Bluetooth devices on the gateway - there may be many, each change of each
    @Test
    void should_ignore_a_sensor_of_another_device_without_a_word() {
        route(GATEWAY + ".status.bthomesensor:240", "{\"id\":240,\"value\":21.5,\"last_updated_ts\":1792135288}");

        assertThat(currentWeather.readings()).isEmpty();
        assertThat(routerLog.list).isEmpty();
        verify(weatherStation, never()).requestSensors();
    }

    //0 until the gateway has heard the object once, as right after AddDevice
    @Test
    void should_take_no_reading_without_a_value_or_a_time() {
        route(GATEWAY + ".status.bthomesensor:205", "{\"id\":205,\"value\":null,\"last_updated_ts\":0}");
        route(GATEWAY + ".status.bthomesensor:205", "{\"id\":205,\"value\":4.4,\"last_updated_ts\":0}");

        assertThat(currentWeather.readings()).isEmpty();
    }

    @Test
    void should_never_store_a_value_of_the_wrong_type() {
        route(GATEWAY + ".status.bthomesensor:208", "{\"id\":208,\"value\":1,\"last_updated_ts\":1792135288}");
        route(GATEWAY + ".status.bthomesensor:205", "{\"id\":205,\"value\":\"warm\",\"last_updated_ts\":1792135288}");

        assertThat(currentWeather.readings()).isEmpty();
        assertThat(routerLog.list).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    }

    //the body of anything else is never read: not JSON, and no error
    @Test
    void should_ignore_every_other_event() {
        route("shellyplus1pm-441793a6aba8.status.bthomesensor:205", "not json");
        route(GATEWAY + ".status.switch:0", "not json");
        route(GATEWAY + ".status.bthomedevice:201", "not json");
        route(GATEWAY + ".online", "not json");
        route("eaton.blinds.45", "not json");
        route(null, "not json");

        assertThat(currentWeather.readings()).isEmpty();
        assertThat(currentWeather.link()).isEmpty();
    }

    @Test
    void should_ignore_every_event_until_the_sensors_are_known() {
        when(weatherStation.sensors()).thenReturn(Optional.empty());

        route(GATEWAY + ".status.bthomesensor:205", "{\"id\":205,\"value\":4.4,\"last_updated_ts\":1792135288}");

        assertThat(currentWeather.readings()).isEmpty();
        assertThat(routerLog.list).isEmpty();
    }

    @Test
    void should_fail_on_a_body_that_is_not_the_status_of_its_topic() {
        sut.route(GATEWAY + ".status.bthomesensor:205", bytes("{\"id\":206,\"value\":4.4}")).as(StepVerifier::create)
            .expectError(ShellyDecodeException.class)
            .verify();
    }

    private void route(final String routingKey, final String body) {
        sut.route(routingKey, bytes(body)).as(StepVerifier::create).verifyComplete();
    }

    private static byte[] bytes(final String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
