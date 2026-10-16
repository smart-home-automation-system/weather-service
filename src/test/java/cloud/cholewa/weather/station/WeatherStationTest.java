package cloud.cholewa.weather.station;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.shelly.model.BthomeDeviceStatus;
import cloud.cholewa.weather.model.StationLink;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.model.SensorsRead;
import cloud.cholewa.weather.reading.CurrentWeather;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.model.StationSensors;
import cloud.cholewa.weather.registry.DeviceRegistryClient;
import cloud.cholewa.weather.registry.RegistryCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cloud.cholewa.weather.registry.StationMapperTestData.gateway;
import static cloud.cholewa.weather.registry.StationMapperTestData.station;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherStationTest {

    private static final StationSensors SENSORS = new StationSensors(Map.of(205, Metric.TEMPERATURE), Set.of(), 200);

    private static final SensorsRead READ = new SensorsRead(SENSORS, Map.of(), null);

    private final DeviceRegistryClient registry = mock(DeviceRegistryClient.class);
    private final SensorMapLoader loader = mock(SensorMapLoader.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T08:00:00Z"));
    private final CurrentWeather currentWeather = new CurrentWeather(clock);
    private final WeatherStation sut = new WeatherStation(registry, loader, currentWeather, clock);

    @BeforeEach
    void setUp() {
        registryHas("shellyplugsg3-28372f2f0460");
        when(loader.load(any())).thenReturn(Mono.just(READ));
    }

    @Test
    void should_know_nothing_before_the_first_read() {
        assertThat(sut.station()).isEmpty();
        assertThat(sut.sensors()).isEmpty();
    }

    @Test
    void should_read_the_station_and_its_sensors() {
        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.station()).map(Station::gatewayDeviceId).contains("shellyplugsg3-28372f2f0460");
        assertThat(sut.sensors()).contains(SENSORS);
    }

    //HAS-237: every refresh reconciles with the gateway - what the broker lost comes back within five
    //minutes, and a re-pairing is found as soon
    @Test
    void should_read_the_gateway_on_every_refresh() {
        sut.refresh().block();
        sut.refresh().block();
        sut.refresh().block();

        verify(loader, times(3)).load(any());
    }

    @Test
    void should_know_when_the_gateway_answered_last() {
        assertThat(sut.gatewayAnsweredAt()).isEmpty();
        sut.refresh().block();
        final Instant answered = clock.instant();
        clock.move(Duration.ofMinutes(5));
        when(loader.load(any())).thenReturn(Mono.error(new IllegalStateException("gateway away")));

        sut.refresh().block();

        assertThat(sut.gatewayAnsweredAt()).contains(answered);
    }

    //what the status of the read API shows: the last read, answered or not
    @Test
    void should_know_the_last_check_of_the_gateway() {
        assertThat(sut.lastCheck()).isEmpty();
        sut.refresh().block();
        assertThat(sut.lastCheck()).hasValueSatisfying(check -> {
            assertThat(check.answered()).isTrue();
            assertThat(check.checkedAt()).isEqualTo(clock.instant());
        });

        clock.move(Duration.ofMinutes(5));
        when(loader.load(any())).thenReturn(Mono.error(new IllegalStateException("gateway away")));
        sut.refresh().block();

        assertThat(sut.lastCheck()).hasValueSatisfying(check -> {
            assertThat(check.answered()).isFalse();
            assertThat(check.checkedAt()).isEqualTo(clock.instant());
        });
    }

    //an answer of the old gateway says nothing about the new one
    @Test
    void should_know_no_answer_of_a_gateway_that_replaced_the_last() {
        sut.refresh().block();
        registryHas("shellypro1pm-8813bfda5e94");
        when(loader.load(any())).thenReturn(Mono.error(new IllegalStateException("gateway away")));

        sut.refresh().block();

        assertThat(sut.gatewayAnsweredAt()).isEmpty();
    }

    //a map read for the old gateway is never the map of the new one
    @Test
    void should_read_the_sensors_again_when_the_gateway_changes() {
        sut.refresh().block();
        registryHas("shellypro1pm-8813bfda5e94");
        when(loader.load(any())).thenReturn(Mono.error(new IllegalStateException("gateway away")));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.station()).map(Station::gatewayDeviceId).contains("shellypro1pm-8813bfda5e94");
        assertThat(sut.sensors()).isEmpty();
    }

    @Test
    void should_keep_the_station_when_the_registry_is_away() {
        sut.refresh().block();
        when(registry.getDevices(DeviceKind.WEATHER_STATION)).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.station()).isPresent();
        assertThat(sut.sensors()).contains(SENSORS);
    }

    @Test
    void should_keep_the_map_when_the_gateway_is_away() {
        sut.refresh().block();
        when(loader.load(any())).thenReturn(Mono.error(new IllegalStateException("gateway away")));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.sensors()).contains(SENSORS);
    }

    @Test
    void should_know_no_station_once_the_registry_has_none() {
        sut.refresh().block();
        when(registry.getDevices(DeviceKind.WEATHER_STATION)).thenReturn(Mono.just(List.of()));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.station()).isEmpty();
        assertThat(sut.sensors()).isEmpty();
    }

    //a value that never changes is never published: it comes with the read of the map
    @Test
    void should_bring_what_the_sensors_hold_now_into_the_state() {
        final Reading battery = Reading.number(new BigDecimal("100"), clock.instant());
        when(loader.load(any())).thenReturn(Mono.just(new SensorsRead(SENSORS, Map.of(Metric.BATTERY, battery), null)));

        sut.refresh().block();

        assertThat(currentWeather.readings()).containsEntry(Metric.BATTERY, battery);
        assertThat(currentWeather.takeSnapshot()).isPresent();
    }

    //the signal and the battery of the station come with the read of the map too, until it publishes
    @Test
    void should_know_the_link_of_the_station_from_the_read_of_the_map() {
        when(loader.load(any())).thenReturn(Mono.just(new SensorsRead(SENSORS, Map.of(),
            BthomeDeviceStatus.builder().id(200).rssi(-73).battery(100).packetId(129).build())));

        sut.refresh().block();

        assertThat(currentWeather.link()).map(StationLink::rssi).contains(-73);
    }

    //the gateway heard the station then, whether a value changed or not - what the watch judges by
    @Test
    void should_know_when_the_gateway_heard_the_station() {
        when(loader.load(any())).thenReturn(Mono.just(new SensorsRead(SENSORS, Map.of(),
            BthomeDeviceStatus.builder().id(200).rssi(-73).lastUpdatedTs(1792135314L).build())));

        sut.refresh().block();

        assertThat(currentWeather.heardAt()).contains(Instant.ofEpochSecond(1792135314L));
    }

    //what the last station measured is not a reading of the new one
    @Test
    void should_forget_the_readings_when_the_station_changes() {
        sut.refresh().block();
        currentWeather.observe(Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), clock.instant()));
        registryHas("shellypro1pm-8813bfda5e94");

        sut.refresh().block();

        assertThat(currentWeather.readings()).isEmpty();
    }

    @Test
    void should_keep_the_readings_when_nothing_changed() {
        sut.refresh().block();
        currentWeather.observe(Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), clock.instant()));

        sut.refresh().block();

        assertThat(currentWeather.readings()).containsKey(Metric.TEMPERATURE);
    }

    //a registry to correct is said once, not every five minutes
    @Test
    void should_say_a_second_station_once() {
        when(registry.getDevices(DeviceKind.WEATHER_STATION)).thenReturn(Mono.just(List.of(
            station(55, "08:b9:5f:d1:e6:71"), station(70, "aa:bb:cc:dd:ee:ff"))));
        final ListAppender<ILoggingEvent> stationLog = new ListAppender<>();
        stationLog.start();
        ((Logger) LoggerFactory.getLogger(WeatherStation.class)).addAppender(stationLog);
        try {
            sut.refresh().block();
            sut.refresh().block();
        } finally {
            ((Logger) LoggerFactory.getLogger(WeatherStation.class)).detachAppender(stationLog);
        }

        assertThat(stationLog.list).filteredOn(event -> event.getLevel() == Level.WARN).singleElement()
            .satisfies(event -> assertThat(event.getFormattedMessage()).contains("weather station [55, 70]"));
        assertThat(sut.station()).map(Station::stationId).contains(55L);
    }

    //the Mono of a @Scheduled method is subscribed again for every run
    @Test
    void should_read_the_registry_again_on_every_subscription() {
        final Mono<Void> refresh = sut.refresh();
        refresh.block();
        refresh.block();

        verify(registry, times(2)).getDevices(DeviceKind.WEATHER_STATION);
        verify(registry, never()).getDevices(DeviceKind.BLINDS);
    }

    private void registryHas(final String gatewayDeviceId) {
        when(registry.getDevices(DeviceKind.WEATHER_STATION)).thenReturn(Mono.just(List.of(station(55, "08:b9:5f:d1:e6:71"))));
        when(registry.getDevices(DeviceKind.BTHOME_GATEWAY)).thenReturn(Mono.just(List.of(gateway(54, gatewayDeviceId))));
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(final Instant now) {
            this.now = now;
        }

        void move(final Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
