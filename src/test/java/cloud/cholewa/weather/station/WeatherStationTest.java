package cloud.cholewa.weather.station;

import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Station;
import cloud.cholewa.weather.model.StationSensors;
import cloud.cholewa.weather.registry.DeviceRegistryClient;
import cloud.cholewa.weather.registry.RegistryCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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

    private static final StationSensors SENSORS = new StationSensors(Map.of(205, Metric.TEMPERATURE), 200);

    private final DeviceRegistryClient registry = mock(DeviceRegistryClient.class);
    private final SensorMapLoader loader = mock(SensorMapLoader.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T08:00:00Z"));
    private final WeatherStation sut = new WeatherStation(registry, loader, clock);

    @BeforeEach
    void setUp() {
        registryHas("shellyplugsg3-28372f2f0460");
        when(loader.load(any())).thenReturn(Mono.just(SENSORS));
    }

    @Test
    void should_know_nothing_before_the_first_read() {
        assertThat(sut.station()).isEmpty();
        assertThat(sut.sensors()).isEmpty();
    }

    @Test
    void should_read_the_station_and_its_sensors() {
        StepVerifier.create(sut.refresh()).verifyComplete();

        assertThat(sut.station()).map(Station::gatewayDeviceId).contains("shellyplugsg3-28372f2f0460");
        assertThat(sut.sensors()).contains(SENSORS);
    }

    //the gateway is called once an hour, not every five minutes
    @Test
    void should_read_the_sensors_again_only_once_an_hour() {
        sut.refresh().block();
        clock.move(Duration.ofMinutes(55));
        sut.refresh().block();
        verify(loader, times(1)).load(any());

        clock.move(Duration.ofMinutes(5));
        sut.refresh().block();
        verify(loader, times(2)).load(any());
    }

    //a map read for the old gateway is never the map of the new one
    @Test
    void should_read_the_sensors_again_when_the_gateway_changes() {
        sut.refresh().block();
        registryHas("shellypro1pm-8813bfda5e94");
        when(loader.load(any())).thenReturn(Mono.error(new IllegalStateException("gateway away")));

        StepVerifier.create(sut.refresh()).verifyComplete();

        assertThat(sut.station()).map(Station::gatewayDeviceId).contains("shellypro1pm-8813bfda5e94");
        assertThat(sut.sensors()).isEmpty();
    }

    @Test
    void should_keep_the_station_when_the_registry_is_away() {
        sut.refresh().block();
        when(registry.getDevices(DeviceKind.WEATHER_STATION)).thenReturn(Mono.error(new RegistryCallException("database-service answered 503")));

        StepVerifier.create(sut.refresh()).verifyComplete();

        assertThat(sut.station()).isPresent();
        assertThat(sut.sensors()).contains(SENSORS);
    }

    @Test
    void should_keep_the_map_when_the_gateway_is_away() {
        sut.refresh().block();
        clock.move(Duration.ofHours(2));
        when(loader.load(any())).thenReturn(Mono.error(new IllegalStateException("gateway away")));

        StepVerifier.create(sut.refresh()).verifyComplete();

        assertThat(sut.sensors()).contains(SENSORS);
    }

    @Test
    void should_know_no_station_once_the_registry_has_none() {
        sut.refresh().block();
        when(registry.getDevices(DeviceKind.WEATHER_STATION)).thenReturn(Mono.just(List.of()));

        StepVerifier.create(sut.refresh()).verifyComplete();

        assertThat(sut.station()).isEmpty();
        assertThat(sut.sensors()).isEmpty();
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
