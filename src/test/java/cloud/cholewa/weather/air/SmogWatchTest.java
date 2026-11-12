package cloud.cholewa.weather.air;

import cloud.cholewa.commons.notification.NotificationPublisher;
import cloud.cholewa.weather.config.AirProperties;
import cloud.cholewa.weather.database.model.AirQualityAlertEntity;
import cloud.cholewa.weather.database.repository.AirQualityAlertRepository;
import cloud.cholewa.weather.model.AirSensor;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmogWatchTest {

    private static final Instant START = Instant.parse("2026-11-12T17:00:00Z");
    private static final AirSensor SENSOR = new AirSensor(145, "Air quality sensor", "10.78.30.23", "aa48fad9faa6");

    private final AirQualityTest.MutableClock clock = new AirQualityTest.MutableClock(START);
    private final AirQuality airQuality = mock(AirQuality.class);
    private final AirQualityAlertRepository repository = mock(AirQualityAlertRepository.class);
    private final NotificationPublisher publisher = mock(NotificationPublisher.class);
    private final SmogWatch sut = new SmogWatch(airQuality, repository, publisher, JsonMapper.builder().build(),
        new AirProperties(Duration.ofMinutes(30), 5, 3, Duration.ofMinutes(30)), clock);

    //air_quality_alert as the repository sees it
    private final AtomicReference<AirQualityAlertEntity> open = new AtomicReference<>();
    private final List<AirQualityAlertEntity> saved = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final List<String> published = new ArrayList<>();
    private final AtomicReference<Map<Metric, Reading>> readings = new AtomicReference<>(Map.of());

    @BeforeEach
    void setUp() {
        when(airQuality.sensor()).thenReturn(Optional.of(SENSOR));
        when(airQuality.confirmedReadings()).thenAnswer(invocation -> readings.get());
        when(publisher.publishHousehold(anyString())).thenAnswer(invocation -> Mono.fromRunnable(() ->
            published.add(invocation.getArgument(0))));
        when(repository.findFirstByEndedAtIsNull()).thenAnswer(invocation -> Mono.fromSupplier(open::get));
        when(repository.save(any(AirQualityAlertEntity.class))).thenAnswer(invocation -> Mono.fromCallable(() -> {
            final AirQualityAlertEntity row = invocation.getArgument(0);
            final AirQualityAlertEntity stored = row.id() == null ? row.toBuilder().id(ids.incrementAndGet()).build() : row;
            saved.add(stored);
            open.set(stored.endedAt() == null ? stored : null);
            return stored;
        }));
    }

    //the sensor measured this level with these particles minutes after the start; the pass runs then
    private void measured(final int level, final int pm10, final int pm25, final long minutes) {
        measuredBy(sut, level, pm10, pm25, minutes);
    }

    private void measuredBy(final SmogWatch watch, final int level, final int pm10, final int pm25, final long minutes) {
        final Instant at = START.plus(Duration.ofMinutes(minutes));
        clock.move(Duration.between(clock.instant(), at.plusSeconds(20)));
        readings.set(Map.of(
            Metric.AIR_QUALITY_LEVEL, Reading.number(BigDecimal.valueOf(level), at),
            Metric.PM10, Reading.number(BigDecimal.valueOf(pm10), at),
            Metric.PM25, Reading.number(BigDecimal.valueOf(pm25), at)));
        watch.check().as(StepVerifier::create).verifyComplete();
    }

    //bad air held for half an hour opens an episode with one household message, the particles of the reading
    @Test
    void should_open_an_episode_of_smog_after_the_level_held_and_tell_the_household_once() {
        measured(5, 105, 70, 0);
        measured(5, 110, 75, 15);
        assertThat(published).isEmpty();

        measured(6, 140, 95, 30);
        measured(6, 150, 99, 45);

        assertThat(published).singleElement().satisfies(json -> assertThat(json)
            .contains("\"event\":\"smog warning\"", "\"subject\":\"Air quality sensor\"", "\"deviceId\":145",
                "\"occurredAt\":\"2026-11-12T17:00:00\"", "\"reminder\":0", "\"pm10\":140", "\"pm25\":95"));
        assertThat(open.get()).satisfies(episode -> {
            assertThat(episode.since()).isEqualTo(START);
            assertThat(episode.level()).isEqualTo(6);
            assertThat(episode.peakPm10()).isEqualTo(150);
            assertThat(episode.peakPm25()).isEqualTo(99);
        });
        assertThat(sut.openEpisode()).contains(open.get());
    }

    //a level between the two breaks the hold: nothing opens
    @Test
    void should_open_nothing_when_the_bad_level_does_not_hold() {
        measured(5, 105, 70, 0);
        measured(4, 80, 50, 20);
        measured(5, 105, 70, 25);
        measured(5, 105, 70, 50);

        assertThat(published).isEmpty();
        assertThat(open.get()).isNull();
    }

    //a clear level held for half an hour closes it with one message, the particles at the end; between the two
    //levels the episode goes on
    @Test
    void should_close_the_episode_after_a_clear_level_held_and_tell_the_household_once() {
        open.set(AirQualityAlertEntity.builder().id(7L).sensorId(145).since(START.minus(Duration.ofHours(2))).level(6)
            .peakPm10(150).peakPm25(99).build());

        measured(4, 70, 45, 0);
        measured(3, 50, 30, 10);
        measured(2, 40, 25, 25);
        assertThat(published).isEmpty();
        measured(2, 35, 20, 40);

        assertThat(published).singleElement().satisfies(json -> assertThat(json)
            .contains("\"event\":\"smog over\"", "\"occurredAt\":\"2026-11-12T17:40:00\"", "\"pm10\":35", "\"pm25\":20"));
        assertThat(saved).last().extracting(AirQualityAlertEntity::endedAt).isEqualTo(START.plus(Duration.ofMinutes(40)));
        assertThat(open.get()).isNull();
        assertThat(sut.openEpisode()).isEmpty();
    }

    //a restart in the middle: the open episode of the database goes on, never a second warning
    @Test
    void should_go_on_with_the_open_episode_after_a_restart() {
        open.set(AirQualityAlertEntity.builder().id(7L).sensorId(145).since(START.minus(Duration.ofHours(1))).level(5)
            .peakPm10(120).peakPm25(80).build());

        measured(5, 110, 75, 0);
        measured(5, 110, 75, 40);

        assertThat(published).isEmpty();
        assertThat(sut.openEpisode()).isPresent();
    }

    //an episode of a sensor the registry no longer has is closed without a word
    @Test
    void should_close_an_episode_of_another_sensor_without_a_word() {
        open.set(AirQualityAlertEntity.builder().id(7L).sensorId(150).since(START.minus(Duration.ofHours(1))).level(5).build());

        measured(2, 20, 12, 0);

        assertThat(published).isEmpty();
        assertThat(saved).singleElement().extracting(AirQualityAlertEntity::endedAt).isNotNull();
    }

    //the message failed: no row, the next pass tries again with the hold still met
    @Test
    void should_open_nothing_when_the_message_failed_and_try_again() {
        when(publisher.publishHousehold(anyString()))
            .thenReturn(Mono.error(new IllegalStateException("broker away")))
            .thenAnswer(invocation -> Mono.fromRunnable(() -> published.add(invocation.getArgument(0))));

        measured(5, 105, 70, 0);
        measured(5, 105, 70, 30);
        assertThat(open.get()).isNull();
        measured(5, 105, 70, 35);

        assertThat(published).hasSize(1);
        assertThat(open.get()).isNotNull();
    }

    //a sensor gone silent on a bad level opens nothing: the hold is judged by the times it measured
    @Test
    void should_judge_the_hold_by_the_times_the_sensor_measured() {
        measured(5, 105, 70, 0);
        clock.move(Duration.ofHours(1));
        sut.check().as(StepVerifier::create).verifyComplete();

        assertThat(published).isEmpty();
    }

    //a gap in the measurements longer than silent-after - the row restored after a restart, a silent sensor - is no
    //hold: a bad level before and after it opens nothing until it held again
    @Test
    void should_start_the_hold_anew_after_a_gap_in_the_measurements() {
        measured(5, 105, 70, 0);
        measured(5, 105, 70, 120);
        assertThat(published).isEmpty();

        measured(5, 105, 70, 150);

        assertThat(published).hasSize(1);
    }

    //the sensor measures every 6-7 minutes: a smog-after shorter than that (5 min is allowed) still opens an episode -
    //the gap is judged by silent-after, never by smog-after (found on the dev queues, 2026-11-12)
    @Test
    void should_open_an_episode_with_a_hold_shorter_than_the_measuring_cycle_of_the_sensor() {
        final SmogWatch quick = new SmogWatch(airQuality, repository, publisher, JsonMapper.builder().build(),
            new AirProperties(Duration.ofMinutes(30), 5, 3, Duration.ofMinutes(5)), clock);

        measuredBy(quick, 5, 105, 70, 0);
        measuredBy(quick, 5, 110, 75, 7);

        assertThat(published).hasSize(1);
    }

    @Test
    void should_judge_nothing_without_a_sensor() {
        when(airQuality.sensor()).thenReturn(Optional.empty());

        sut.check().as(StepVerifier::create).verifyComplete();

        verify(repository, never()).findFirstByEndedAtIsNull();
    }
}
