package cloud.cholewa.weather.air;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.home.model.BleboxBinding;
import cloud.cholewa.home.model.DeviceKind;
import cloud.cholewa.home.model.RoomName;
import cloud.cholewa.home.model.SmartDevice;
import cloud.cholewa.home.model.SmartVendor;
import cloud.cholewa.weather.model.AirSensor;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import cloud.cholewa.weather.registry.DeviceRegistryClient;
import cloud.cholewa.weather.registry.RegistryCallException;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AirQualityTest {

    private static final Instant START = Instant.parse("2026-11-12T13:00:00Z");
    private static final AirSensor SENSOR = new AirSensor(145, "Air quality sensor", "10.78.30.23", "aa48fad9faa6");

    private final MutableClock clock = new MutableClock(START);
    private final DeviceRegistryClient registry = mock(DeviceRegistryClient.class);
    private final AirSensorClient client = mock(AirSensorClient.class);
    private final AirQuality sut = new AirQuality(registry, client, clock);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    @BeforeEach
    void setUp() {
        log.start();
        ((Logger) LoggerFactory.getLogger(AirQuality.class)).addAppender(log);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(AirQuality.class)).detachAppender(log);
    }

    private static SmartDevice device(final long id, final String address) {
        return SmartDevice.builder().id(id).name("Air quality sensor").room(RoomName.GARDEN)
            .kind(DeviceKind.AIR_QUALITY_SENSOR).enabled(true).vendor(SmartVendor.BLEBOX)
            .blebox(BleboxBinding.builder().address(address).deviceId("aa48fad9faa6").build()).build();
    }

    private static AirStateResponse state(final int pm10, final long elapsed) {
        return new AirStateResponse(new AirStateResponse.Air(1,
            List.of(new AirStateResponse.Sensor("pm10", pm10, 3, 0, 1, elapsed))));
    }

    private void registryHas(final SmartDevice... devices) {
        when(registry.getDevices(DeviceKind.AIR_QUALITY_SENSOR)).thenReturn(Mono.just(List.of(devices)));
        sut.refresh().as(StepVerifier::create).verifyComplete();
    }

    private List<String> warnings() {
        return log.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void should_read_the_sensor_of_the_registry_and_hold_its_readings() {
        registryHas(device(145, "10.78.30.23"));
        when(client.state("10.78.30.23")).thenReturn(Mono.just(state(12, 60)));

        sut.poll().as(StepVerifier::create).verifyComplete();

        assertThat(sut.sensor()).contains(SENSOR);
        assertThat(sut.confirmedReadings()).containsEntry(Metric.PM10,
            Reading.number(new BigDecimal("12"), START.minusSeconds(60)));
        assertThat(sut.lastCheck()).contains(new AirQuality.Check(SENSOR, START, true));
        assertThat(sut.lastReadingAt()).contains(START.minusSeconds(60));
    }

    //the registry has no sensor: nothing is read, nothing is known
    @Test
    void should_read_nothing_without_a_sensor_in_the_registry() {
        registryHas();

        sut.poll().as(StepVerifier::create).verifyComplete();

        verify(client, never()).state(any());
        assertThat(sut.sensor()).isEmpty();
        assertThat(warnings()).containsExactly("The device registry has no switched-on air quality sensor with a BleBox binding");
    }

    //the same value read again a minute later is the same measurement confirmed, not a new one
    @Test
    void should_confirm_the_same_value_with_its_later_time() {
        registryHas(device(145, "10.78.30.23"));
        when(client.state("10.78.30.23")).thenReturn(Mono.just(state(12, 300)), Mono.just(state(12, 20)));

        sut.poll().as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(1));
        sut.poll().as(StepVerifier::create).verifyComplete();

        assertThat(sut.confirmedReadings().get(Metric.PM10).measuredAt()).isEqualTo(START.plusSeconds(40));
        assertThat(sut.lastReadingAt()).contains(START.plusSeconds(40));
    }

    @Test
    void should_take_a_new_value_measured_later() {
        registryHas(device(145, "10.78.30.23"));
        when(client.state("10.78.30.23")).thenReturn(Mono.just(state(12, 300)), Mono.just(state(55, 20)));

        sut.poll().as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(1));
        sut.poll().as(StepVerifier::create).verifyComplete();

        assertThat(sut.confirmedReadings())
            .containsEntry(Metric.PM10, Reading.number(new BigDecimal("55"), START.plusSeconds(40)));
    }

    //a failed read keeps the readings, says so once an hour per kind, and is never an error of the pass
    @Test
    void should_keep_the_readings_of_a_failed_read_and_warn_once_an_hour_per_kind() {
        registryHas(device(145, "10.78.30.23"));
        when(client.state("10.78.30.23")).thenReturn(Mono.just(state(12, 60)),
            Mono.error(new AirSensorCallException(AirSensorCallException.Kind.TIMEOUT, "the air sensor did not answer in time")),
            Mono.error(new AirSensorCallException(AirSensorCallException.Kind.TIMEOUT, "the air sensor did not answer in time")),
            Mono.error(new AirSensorCallException(AirSensorCallException.Kind.CONNECTION, "the air sensor could not be reached")),
            Mono.error(new AirSensorCallException(AirSensorCallException.Kind.TIMEOUT, "the air sensor did not answer in time")));

        sut.poll().as(StepVerifier::create).verifyComplete();
        for (int pass = 0; pass < 3; pass++) {
            clock.move(Duration.ofMinutes(1));
            sut.poll().as(StepVerifier::create).verifyComplete();
        }
        clock.move(Duration.ofHours(1));
        sut.poll().as(StepVerifier::create).verifyComplete();

        assertThat(sut.confirmedReadings()).containsKey(Metric.PM10);
        assertThat(sut.lastCheck()).get().extracting(AirQuality.Check::answered).isEqualTo(false);
        assertThat(warnings()).containsExactly(
            "Read of the air quality sensor 145 at 10.78.30.23 failed, the last readings stay: the air sensor did not answer in time",
            "Read of the air quality sensor 145 at 10.78.30.23 failed, the last readings stay: the air sensor could not be reached",
            "Read of the air quality sensor 145 at 10.78.30.23 failed, the last readings stay: the air sensor did not answer in time");
    }

    //another sensor: what the last one measured is not a reading of this one
    @Test
    void should_clear_the_readings_when_another_sensor_replaces_it() {
        registryHas(device(145, "10.78.30.23"));
        when(client.state("10.78.30.23")).thenReturn(Mono.just(state(12, 60)));
        sut.poll().as(StepVerifier::create).verifyComplete();

        registryHas(device(146, "10.78.30.24"));

        assertThat(sut.confirmedReadings()).isEmpty();
        assertThat(sut.lastCheck()).isEmpty();
        assertThat(sut.sensor()).get().extracting(AirSensor::id).isEqualTo(146L);
    }

    //HAS-428: the readings of the latest row, restored before the first answer of the registry, stay with it
    @Test
    void should_keep_the_restored_readings_through_the_first_read_of_the_registry() {
        sut.restore(Map.of(Metric.PM25, Reading.number(new BigDecimal("30"), START.minusSeconds(120))));

        registryHas(device(145, "10.78.30.23"));

        assertThat(sut.readings()).containsOnlyKeys(Metric.PM25);
        assertThat(sut.takeChange()).isFalse();
    }

    //a failed read of the registry keeps the last sensor
    @Test
    void should_keep_the_sensor_when_the_registry_cannot_be_read() {
        registryHas(device(145, "10.78.30.23"));
        when(registry.getDevices(DeviceKind.AIR_QUALITY_SENSOR))
            .thenReturn(Mono.error(new RegistryCallException("database-service answered 500")));

        sut.refresh().as(StepVerifier::create).verifyComplete();

        assertThat(sut.sensor()).contains(SENSOR);
    }

    //a second sensor is said once, the lowest id is used
    @Test
    void should_warn_once_about_a_second_sensor() {
        registryHas(device(150, "10.78.30.24"), device(145, "10.78.30.23"));
        registryHas(device(150, "10.78.30.24"), device(145, "10.78.30.23"));

        assertThat(sut.sensor()).get().extracting(AirSensor::id).isEqualTo(145L);
        assertThat(warnings()).containsExactly(
            "The device registry has more than one switched-on device of a kind, the one with the lowest id is used: "
                + "air quality sensor [145, 150]");
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
