package cloud.cholewa.weather.reading;

import cloud.cholewa.weather.config.RetentionProperties;
import cloud.cholewa.weather.database.repository.WeatherReadingRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeatherRetentionTest {

    private final WeatherReadingRepository repository = mock(WeatherReadingRepository.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-17T01:10:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Warsaw");
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final WeatherRetention sut = new WeatherRetention(repository, new RetentionProperties(Duration.ofDays(730)), clock);

    //house time, as measured_at is stored. The Mono of a @Scheduled method is subscribed again every
    //night: each night counts back from its own date
    @Test
    void should_delete_the_rows_past_the_retention_counted_from_each_night() {
        when(repository.deleteOlderThan(any())).thenReturn(Mono.just(12L));
        final Mono<Void> purge = sut.purge();

        purge.as(StepVerifier::create).verifyComplete();
        now.set(now.get().plus(Duration.ofDays(1)));
        purge.as(StepVerifier::create).verifyComplete();

        verify(repository).deleteOlderThan(LocalDateTime.of(2024, 10, 17, 3, 10));
        verify(repository).deleteOlderThan(LocalDateTime.of(2024, 10, 18, 3, 10));
    }

    @Test
    void should_complete_when_the_delete_fails() {
        when(repository.deleteOlderThan(any())).thenReturn(Mono.error(new IllegalStateException("database away")));

        sut.purge().as(StepVerifier::create).verifyComplete();
    }
}
