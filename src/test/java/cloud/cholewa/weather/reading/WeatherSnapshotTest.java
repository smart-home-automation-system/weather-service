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
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherSnapshotTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 9, 21, 28);

    private final CurrentWeather currentWeather = new CurrentWeather();
    private final WeatherReadingRepository repository = mock(WeatherReadingRepository.class);
    private final WeatherSnapshot sut = new WeatherSnapshot(currentWeather, repository);

    @Test
    void should_store_the_state_when_a_reading_arrived() {
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        currentWeather.observe(Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), AT));

        StepVerifier.create(sut.snapshot()).verifyComplete();

        final ArgumentCaptor<WeatherReadingEntity> saved = ArgumentCaptor.forClass(WeatherReadingEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().measuredAt()).isEqualTo(AT);
        assertThat(saved.getValue().temperature()).isEqualByComparingTo("4.4");
    }

    //the Mono of a @Scheduled method is subscribed again for every run: the second run sees the
    //state of its own minute, and stores nothing when nothing arrived
    @Test
    void should_store_a_row_per_minute_with_something_new_only() {
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.empty());
        when(repository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        final Mono<Void> pass = sut.snapshot();

        currentWeather.observe(Metric.WIND, Reading.number(new BigDecimal("1.1"), AT));
        pass.block();
        pass.block();
        currentWeather.observe(Metric.WIND, Reading.number(new BigDecimal("1.4"), AT.plusMinutes(1)));
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
            .id(7L).measuredAt(AT).pressure(new BigDecimal("993.30")).build()));

        sut.snapshot().block();
        sut.snapshot().block();

        assertThat(currentWeather.readings()).containsKey(Metric.PRESSURE);
        verify(repository, times(1)).findFirstByOrderByIdDesc();
        verify(repository, never()).save(any());
    }

    @Test
    void should_try_the_restore_again_when_it_failed() {
        when(repository.findFirstByOrderByIdDesc())
            .thenReturn(Mono.error(new IllegalStateException("database away")))
            .thenReturn(Mono.empty());

        StepVerifier.create(sut.snapshot()).verifyComplete();
        StepVerifier.create(sut.snapshot()).verifyComplete();

        verify(repository, times(2)).findFirstByOrderByIdDesc();
    }

    @Test
    void should_keep_the_readings_for_the_next_pass_when_the_insert_fails() {
        when(repository.findFirstByOrderByIdDesc()).thenReturn(Mono.empty());
        when(repository.save(any()))
            .thenReturn(Mono.error(new IllegalStateException("database away")))
            .thenAnswer(call -> Mono.just(call.getArgument(0)));
        currentWeather.observe(Metric.TEMPERATURE, Reading.number(new BigDecimal("4.4"), AT));

        StepVerifier.create(sut.snapshot()).verifyComplete();
        StepVerifier.create(sut.snapshot()).verifyComplete();

        verify(repository, times(2)).save(any());
    }
}
