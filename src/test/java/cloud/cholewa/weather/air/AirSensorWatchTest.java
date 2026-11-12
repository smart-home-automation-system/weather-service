package cloud.cholewa.weather.air;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.weather.config.AirProperties;
import cloud.cholewa.weather.config.MonitorProperties;
import cloud.cholewa.weather.database.model.StationAlertEntity;
import cloud.cholewa.weather.database.repository.StationAlertRepository;
import cloud.cholewa.weather.model.AirSensor;
import cloud.cholewa.weather.monitor.Watch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AirSensorWatchTest {

    //10:00 house time (UTC in the clock of the test)
    private static final Instant START = Instant.parse("2026-11-12T10:00:00Z");
    private static final AirSensor SENSOR = new AirSensor(145, "Air quality sensor", "10.78.30.23", "aa48fad9faa6");

    private final AirQualityTest.MutableClock clock = new AirQualityTest.MutableClock(START);
    private final AirQuality airQuality = mock(AirQuality.class);
    private final StationAlertRepository repository = mock(StationAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final AirSensorWatch sut = new AirSensorWatch(airQuality, repository, publisher,
        new AirProperties(Duration.ofMinutes(30), 5, 3, Duration.ofMinutes(30)),
        new MonitorProperties(Duration.ofMinutes(30), Duration.ofHours(1)), clock);

    private final Map<Long, StationAlertEntity> rows = new TreeMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicReference<Instant> lastReading = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(airQuality.sensor()).thenReturn(Optional.of(SENSOR));
        when(airQuality.lastReadingAt()).thenAnswer(invocation -> Optional.ofNullable(lastReading.get()));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.empty());
        when(publisher.publishReminder(anyString())).thenReturn(Mono.empty());
        when(publisher.publishInfo(anyString())).thenReturn(Mono.empty());
        when(repository.findAll()).thenAnswer(invocation -> Flux.defer(() -> Flux.fromIterable(rows.values())));
        when(repository.save(any(StationAlertEntity.class))).thenAnswer(invocation -> Mono.fromCallable(() -> {
            final StationAlertEntity row = invocation.getArgument(0);
            final StationAlertEntity saved = row.id() == null
                ? new StationAlertEntity(ids.incrementAndGet(), row.stationId(), row.watch(), row.since(), row.lastAlertAt())
                : row;
            rows.put(saved.id(), saved);
            return saved;
        }));
        when(repository.delete(any(StationAlertEntity.class))).thenAnswer(invocation -> Mono.fromRunnable(() ->
            rows.remove(invocation.<StationAlertEntity>getArgument(0).id())));
    }

    @Test
    void should_say_nothing_while_the_sensor_measures() {
        lastReading.set(START.minus(Duration.ofMinutes(6)));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
        assertThat(sut.silent()).isFalse();
    }

    @Test
    void should_alert_once_reminding_every_hour_and_say_once_when_it_measures_again() {
        lastReading.set(START.minus(Duration.ofMinutes(31)));

        sut.check().as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(30));
        sut.check().as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(31));
        sut.check().as(StepVerifier::create).verifyComplete();
        lastReading.set(clock.instant().minusSeconds(40));
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishAlert("The air quality sensor Air quality sensor has given no valid reading since 2026-11-12 09:29 (31 min).");
        verify(publisher).publishReminder("The air quality sensor Air quality sensor has still given no valid reading - since 2026-11-12 09:29 (1 h 32 min).");
        verify(publisher).publishInfo("The air quality sensor Air quality sensor gives valid readings again. None came from 2026-11-12 09:29 to 2026-11-12 11:00 (1 h 31 min).");
        assertThat(rows).isEmpty();
        assertThat(sut.silent()).isFalse();
    }

    //never measured since the start: counted from the start of the watch, and the text says so
    @Test
    void should_count_from_the_start_when_the_sensor_never_measured() {
        sut.check().as(StepVerifier::create).verifyComplete();
        clock.move(Duration.ofMinutes(30));
        sut.check().as(StepVerifier::create).verifyComplete();

        verify(publisher).publishAlert(
            "The air quality sensor Air quality sensor has given no valid reading since the service started watching it at 2026-11-12 10:00 (30 min).");
        assertThat(rows.values()).extracting(StationAlertEntity::watch, StationAlertEntity::stationId)
            .containsExactly(tuple(Watch.AIR_SENSOR, 145L));
        assertThat(sut.silent()).isTrue();
    }

    //the alerts of the station live in the same table and are not the sensor's
    @Test
    void should_leave_the_alerts_of_the_station_alone() {
        lastReading.set(START);
        rows.put(1L, new StationAlertEntity(1L, 55L, Watch.STATION, START.minus(Duration.ofHours(1)), START.minus(Duration.ofHours(1))));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
        assertThat(rows).containsOnlyKeys(1L);
    }

    //a row about a sensor the registry no longer has is closed without a word
    @Test
    void should_close_an_alert_of_another_sensor_without_a_word() {
        lastReading.set(START);
        rows.put(1L, new StationAlertEntity(1L, 150L, Watch.AIR_SENSOR, START.minus(Duration.ofHours(1)), START.minus(Duration.ofHours(1))));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
        assertThat(rows).isEmpty();
    }

    //a notification that failed is tried again by the next pass; the row is written only after it
    @Test
    void should_alert_again_with_the_next_pass_when_the_alert_failed() {
        lastReading.set(START.minus(Duration.ofMinutes(40)));
        when(publisher.publishAlert(anyString())).thenReturn(Mono.error(new IllegalStateException("broker away")), Mono.empty());

        sut.check().as(StepVerifier::create).verifyComplete();
        assertThat(rows).isEmpty();
        sut.check().as(StepVerifier::create).verifyComplete();

        assertThat(rows).hasSize(1);
    }

    @Test
    void should_judge_nothing_without_a_sensor() {
        when(airQuality.sensor()).thenReturn(Optional.empty());

        sut.check().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).findAll();
        verifyNoInteractions(publisher);
    }

    @Test
    void should_complete_when_the_alerts_cannot_be_read() {
        when(repository.findAll()).thenReturn(Flux.error(new IllegalStateException("database away")));

        sut.check().as(StepVerifier::create).verifyComplete();

        verifyNoInteractions(publisher);
    }
}
