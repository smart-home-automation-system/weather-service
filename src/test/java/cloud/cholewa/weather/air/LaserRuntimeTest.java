package cloud.cholewa.weather.air;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.weather.config.LaserProperties;
import cloud.cholewa.weather.database.model.AirSensorRuntimeEntity;
import cloud.cholewa.weather.database.repository.AirSensorRuntimeRepository;
import cloud.cholewa.weather.model.AirSensor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LaserRuntimeTest {

    private static final Instant START = Instant.parse("2026-11-13T09:00:00Z");
    private static final AirSensor SENSOR = new AirSensor(145, "Air quality sensor", "10.78.30.23", "aa48fad9faa6");
    private static final long LIFE = 8000;
    private static final AirSensor OTHER = new AirSensor(146, "New air quality sensor", "10.78.30.24", "aa48fad9faa7");

    private final AirQualityTest.MutableClock clock = new AirQualityTest.MutableClock(START);
    private final AirQuality airQuality = mock(AirQuality.class);
    private final AirSensorClient client = mock(AirSensorClient.class);
    private final AirSensorRuntimeRepository repository = mock(AirSensorRuntimeRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final LaserProperties properties = new LaserProperties(8000L, 7000L);

    //air_sensor_runtime as the repository sees it, by the sensor
    private final Map<Long, AirSensorRuntimeEntity> rows = new HashMap<>();
    private final List<AirSensorRuntimeEntity> saved = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final List<String> reminders = new ArrayList<>();
    private final List<String> infos = new ArrayList<>();
    private final AtomicBoolean brokerDown = new AtomicBoolean();

    private final LaserRuntime sut = new LaserRuntime(airQuality, client, repository, publisher, properties, clock);

    @BeforeEach
    void setUp() {
        when(airQuality.sensor()).thenReturn(Optional.of(SENSOR));
        when(publisher.publishReminder(anyString())).thenAnswer(invocation -> brokerDown.get()
            ? Mono.error(new IllegalStateException("broker down"))
            : Mono.fromRunnable(() -> reminders.add(invocation.getArgument(0))));
        when(publisher.publishInfo(anyString())).thenAnswer(invocation -> brokerDown.get()
            ? Mono.error(new IllegalStateException("broker down"))
            : Mono.fromRunnable(() -> infos.add(invocation.getArgument(0))));
        when(repository.findBySensorId(anyLong())).thenAnswer(invocation -> Mono.justOrEmpty(rows.get((long) invocation.getArgument(0))));
        when(repository.save(any(AirSensorRuntimeEntity.class))).thenAnswer(invocation -> Mono.fromCallable(() -> {
            final AirSensorRuntimeEntity row = invocation.getArgument(0);
            final AirSensorRuntimeEntity stored = row.id() == null ? row.toBuilder().id(ids.incrementAndGet()).build() : row;
            rows.put(stored.sensorId(), stored);
            saved.add(stored);
            return stored;
        }));
    }

    //the counter read an hour after the pass before
    private void read(final long hours) {
        clock.move(Duration.ofHours(1));
        when(client.runtimeHours(SENSOR.address())).thenReturn(Mono.just(hours));
        sut.check().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_the_first_read_and_show_it() {
        read(1908);

        assertThat(saved).singleElement().satisfies(row -> {
            assertThat(row.sensorId()).isEqualTo(145);
            assertThat(row.hours()).isEqualTo(1908);
            assertThat(row.readAt()).isEqualTo(START.plus(Duration.ofHours(1)));
            assertThat(row.warnedAtHours()).isNull();
        });
        assertThat(sut.laser()).contains(new LaserRuntime.Laser(145, 1908, START.plus(Duration.ofHours(1)), LIFE, false));
        assertThat(reminders).isEmpty();
        assertThat(infos).isEmpty();
    }

    //the status table rule: a row on a change only - the time of the read is held in memory
    @Test
    void should_store_nothing_while_the_counter_stands_still() {
        read(1908);
        read(1908);

        assertThat(saved).hasSize(1);
        assertThat(sut.laser()).contains(new LaserRuntime.Laser(145, 1908, START.plus(Duration.ofHours(2)), LIFE, false));
    }

    @Test
    void should_store_another_hour() {
        read(1908);
        read(1909);

        assertThat(saved).extracting(AirSensorRuntimeEntity::hours).containsExactly(1908L, 1909L);
        assertThat(saved).extracting(AirSensorRuntimeEntity::id).containsOnly(1L);
    }

    @Test
    void should_warn_once_at_the_threshold_and_again_every_500_hours() {
        read(6999);
        read(7000);
        read(7001);
        read(7499);
        read(7500);
        read(7999);
        read(8000);

        assertThat(reminders).containsExactly(
            "The laser of the air quality sensor Air quality sensor has run 7000 h, of the about 8000 h of its life. Order a new sensor.",
            "The laser of the air quality sensor Air quality sensor has run 7500 h now, of the about 8000 h of its life. Order a new sensor.",
            "The laser of the air quality sensor Air quality sensor has run 8000 h now, of the about 8000 h of its life. Order a new sensor.");
        assertThat(rows.get(145L).warnedAtHours()).isEqualTo(8000L);
        verify(publisher, never()).publishAlert(anyString());
    }

    //a first read far above the threshold is one warning of the step it has reached, not one per step
    @Test
    void should_warn_once_for_a_counter_first_read_above_the_threshold() {
        read(7730);
        read(7731);

        assertThat(reminders).containsExactly(
            "The laser of the air quality sensor Air quality sensor has run 7730 h, of the about 8000 h of its life. Order a new sensor.");
        assertThat(rows.get(145L).warnedAtHours()).isEqualTo(7500L);
    }

    @Test
    void should_not_repeat_a_warning_after_a_restart() {
        rows.put(145L, AirSensorRuntimeEntity.builder().id(1L).sensorId(145).hours(7200).readAt(START).warnedAtHours(7000L).build());

        read(7201);

        assertThat(reminders).isEmpty();
        assertThat(rows.get(145L).hours()).isEqualTo(7201);
        assertThat(rows.get(145L).warnedAtHours()).isEqualTo(7000L);
    }

    //the row brings the counter back before the sensor answers - /status shows it at once
    @Test
    void should_restore_the_counter_even_when_the_sensor_does_not_answer() {
        rows.put(145L, AirSensorRuntimeEntity.builder().id(1L).sensorId(145).hours(1908).readAt(START).build());
        when(client.runtimeHours(SENSOR.address())).thenReturn(Mono.error(
            new AirSensorCallException(AirSensorCallException.Kind.TIMEOUT, "the air sensor did not answer in time")));

        sut.check().as(StepVerifier::create).verifyComplete();

        assertThat(sut.laser()).contains(new LaserRuntime.Laser(145, 1908, START, LIFE, false));
        assertThat(saved).isEmpty();
    }

    @Test
    void should_keep_the_last_value_after_a_failed_read() {
        read(1908);
        when(client.runtimeHours(SENSOR.address())).thenReturn(Mono.error(
            new AirSensorCallException(AirSensorCallException.Kind.INVALID_BODY, "the air sensor answered without the whole hours of its laser")));
        clock.move(Duration.ofHours(1));

        sut.check().as(StepVerifier::create).verifyComplete();

        assertThat(sut.laser()).contains(new LaserRuntime.Laser(145, 1908, START.plus(Duration.ofHours(1)), LIFE, false));
        assertThat(saved).hasSize(1);
    }

    //a replaced or reset sensor: told once, the new value taken, the warnings start anew
    @Test
    void should_tell_a_dropped_counter_and_start_the_warnings_anew() {
        read(7600);
        read(12);

        assertThat(infos).containsExactly(
            "The laser counter of the air quality sensor Air quality sensor dropped from 7600 h to 12 h: the sensor was replaced or reset.");
        assertThat(rows.get(145L).hours()).isEqualTo(12);
        assertThat(rows.get(145L).warnedAtHours()).isNull();

        read(7000);
        assertThat(reminders).hasSize(2);
    }

    @Test
    void should_tell_a_drop_seen_across_a_restart() {
        rows.put(145L, AirSensorRuntimeEntity.builder().id(1L).sensorId(145).hours(7600).readAt(START).warnedAtHours(7500L).build());

        read(3);

        assertThat(infos).hasSize(1);
        assertThat(rows.get(145L).warnedAtHours()).isNull();
    }

    //notification first, row after: a warning the broker did not take is told again with the next pass
    @Test
    void should_tell_a_warning_again_when_the_broker_did_not_take_it() {
        read(6999);
        brokerDown.set(true);
        read(7000);

        assertThat(rows.get(145L).hours()).isEqualTo(6999);
        assertThat(rows.get(145L).warnedAtHours()).isNull();
        assertThat(sut.laser()).map(LaserRuntime.Laser::hours).contains(6999L);

        brokerDown.set(false);
        read(7001);

        assertThat(reminders).hasSize(1);
        assertThat(rows.get(145L).warnedAtHours()).isEqualTo(7000L);
    }

    @Test
    void should_end_a_notification_that_hangs() {
        read(6999);
        clock.move(Duration.ofHours(1));
        when(publisher.publishReminder(anyString())).thenReturn(Mono.never());
        when(client.runtimeHours(SENSOR.address())).thenReturn(Mono.just(7000L));

        StepVerifier.withVirtualTime(sut::check)
            .expectSubscription()
            .thenAwait(LaserRuntime.PUBLISH_TIMEOUT)
            .verifyComplete();

        assertThat(rows.get(145L).hours()).isEqualTo(6999);
    }

    @Test
    void should_complete_when_the_row_cannot_be_read() {
        when(repository.findBySensorId(anyLong())).thenReturn(Mono.error(new IllegalStateException("pool exhausted")));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(client);
    }

    //the counter of another sensor is never shown as this one's; its own row is read back
    @Test
    void should_follow_another_sensor_of_the_registry() {
        read(7600);
        when(airQuality.sensor()).thenReturn(Optional.of(OTHER));

        assertThat(sut.laser()).isEmpty();

        clock.move(Duration.ofHours(1));
        when(client.runtimeHours(OTHER.address())).thenReturn(Mono.just(4L));
        sut.check().as(StepVerifier::create).verifyComplete();

        assertThat(sut.laser()).contains(new LaserRuntime.Laser(146, 4, START.plus(Duration.ofHours(2)), LIFE, false));
        assertThat(infos).isEmpty();
        assertThat(rows).containsKeys(145L, 146L);
    }

    @Test
    void should_read_nothing_without_a_sensor_in_the_registry() {
        when(airQuality.sensor()).thenReturn(Optional.empty());

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(client, repository, publisher);
        assertThat(sut.laser()).isEmpty();
    }

    //Spring subscribes again to the same Mono: the sensor and the time are read at subscription
    @Test
    void should_read_the_time_anew_for_every_subscription() {
        when(client.runtimeHours(SENSOR.address())).thenReturn(Mono.just(1908L));
        final Mono<Void> check = sut.check();

        check.as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofHours(1));
        check.as(StepVerifier::create).verifyComplete();

        assertThat(sut.laser()).map(LaserRuntime.Laser::readAt).contains(START.plus(Duration.ofHours(1)));
    }

    @Test
    void should_show_the_warning_from_the_threshold() {
        read(6999);
        assertThat(sut.laser()).map(LaserRuntime.Laser::warn).contains(false);

        read(7000);
        assertThat(sut.laser()).map(LaserRuntime.Laser::warn).contains(true);
        assertThat(sut.laser()).map(LaserRuntime.Laser::lifeHours).contains(LIFE);
    }

    //the pass looks every five minutes; the sensor is asked once an hour, a failed read too
    @Test
    void should_ask_the_sensor_once_an_hour() {
        when(client.runtimeHours(SENSOR.address())).thenReturn(Mono.error(
            new AirSensorCallException(AirSensorCallException.Kind.TIMEOUT, "the air sensor did not answer in time")));

        sut.check().as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(5));
        sut.check().as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(55));
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(client, times(2)).runtimeHours(SENSOR.address());
    }

    //a start whose first read of the registry had no sensor asks it with the first pass that has one
    @Test
    void should_ask_the_sensor_as_soon_as_the_registry_has_it() {
        when(airQuality.sensor()).thenReturn(Optional.empty());
        sut.check().as(StepVerifier::create).verifyComplete();

        when(airQuality.sensor()).thenReturn(Optional.of(SENSOR));
        when(client.runtimeHours(SENSOR.address())).thenReturn(Mono.just(1908L));
        clock.move(Duration.ofMinutes(5));
        sut.check().as(StepVerifier::create).verifyComplete();

        assertThat(sut.laser()).map(LaserRuntime.Laser::hours).contains(1908L);
    }
}
