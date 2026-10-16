package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import cloud.cholewa.weather.database.repository.WeatherReadingRepository;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherSnapshotTest {

    private static final Instant AT = Instant.parse("2026-10-16T07:21:28Z");
    private static final Clock CLOCK = Clock.fixed(AT, ZoneId.of("Europe/Warsaw"));

    private final CurrentWeather currentWeather = new CurrentWeather();
    private final WeatherReadingRepository repository = mock(WeatherReadingRepository.class);
    private final WeatherSnapshot sut = new WeatherSnapshot(currentWeather, repository, CLOCK);

    @Test
    void should_store_the_state_at_house_time_when_something_changed() {
        nothingStored();
        currentWeather.observe(Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), AT));

        sut.snapshot().as(StepVerifier::create).verifyComplete();

        final ArgumentCaptor<WeatherReadingEntity> saved = ArgumentCaptor.forClass(WeatherReadingEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().measuredAt()).isEqualTo(LocalDateTime.of(2026, 10, 16, 9, 21, 28));
        assertThat(saved.getValue().temperature()).isEqualByComparingTo("4.4");
    }

    //the Mono of a @Scheduled method is subscribed again for every run: the second run sees the
    //state of its own minute, and stores nothing when nothing changed
    @Test
    void should_store_a_row_per_minute_with_something_new_only() {
        nothingStored();
        final Mono<Void> pass = sut.snapshot();

        currentWeather.observe(Metric.WIND, Reading.number(new BigDecimal("1.1"), AT));
        pass.block();
        pass.block();
        currentWeather.observe(Metric.WIND, Reading.number(new BigDecimal("1.4"), AT.plusSeconds(60)));
        pass.block();

        final ArgumentCaptor<WeatherReadingEntity> saved = ArgumentCaptor.forClass(WeatherReadingEntity.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(WeatherReadingEntity::wind)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("1.1"), new BigDecimal("1.4"));
        verify(repository, times(1)).findFirstByOrderByIdDesc();
    }

    @Test
    void should_restore_the_state_from_the_latest_row_once() {
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.just(WeatherReadingEntity.builder()
            .id(7L).measuredAt(LocalDateTime.of(2026, 10, 16, 9, 0)).pressure(new BigDecimal("993.30")).build()));

        sut.snapshot().block();
        sut.snapshot().block();

        assertThat(currentWeather.readings()).containsKey(Metric.PRESSURE);
        verify(repository, times(1)).findFirstByOrderByIdDesc();
        verify(repository, never()).save(any());
    }

    //a row of the few readings since the restart would become the latest one, and the next restore
    //would read it instead of the whole state
    @Test
    void should_store_nothing_before_the_state_was_restored() {
        when(repository.findFirstByOrderByIdDesc())
            .thenReturn(Mono.error(new IllegalStateException("database away")))
            .thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        currentWeather.observe(Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), AT));

        sut.snapshot().as(StepVerifier::create).verifyComplete();
        verify(repository, never()).save(any());

        sut.snapshot().as(StepVerifier::create).verifyComplete();
        verify(repository, times(2)).findFirstByOrderByIdDesc();
        verify(repository, times(1)).save(any());
    }

    @Test
    void should_keep_the_readings_for_the_next_pass_when_the_insert_fails() {
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.empty());
        when(repository.save(any()))
            .thenReturn(Mono.error(new IllegalStateException("database away")))
            .thenAnswer(call -> Mono.just(call.getArgument(0)));
        currentWeather.observe(Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), AT));

        sut.snapshot().as(StepVerifier::create).verifyComplete();
        sut.snapshot().as(StepVerifier::create).verifyComplete();

        verify(repository, times(2)).save(any());
    }

    //the gateway publishes a sensor only on a change: one the stopping pod took and never stored
    //would be lost until the next one
    @Test
    void should_store_the_state_once_more_when_the_pod_stops() throws InterruptedException {
        nothingStored();
        sut.snapshot().block();
        currentWeather.observe(Metric.RAIN, Reading.flag(false, AT));
        final CountDownLatch stopped = new CountDownLatch(1);

        sut.stop(stopped::countDown);

        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        verify(repository, times(1)).save(any());
        assertThat(sut.isRunning()).isFalse();
    }

    @Test
    void should_stop_without_a_store_before_the_restore() throws InterruptedException {
        currentWeather.observe(Metric.RAIN, Reading.flag(false, AT));
        final CountDownLatch stopped = new CountDownLatch(1);

        sut.stop(stopped::countDown);

        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        verify(repository, never()).save(any());
    }

    //it stops after the container of the listener, whose phase is the highest
    @Test
    void should_stop_after_the_listener() {
        assertThat(sut.getPhase()).isLessThan(Integer.MAX_VALUE);
    }

    private void nothingStored() {
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    }
}
