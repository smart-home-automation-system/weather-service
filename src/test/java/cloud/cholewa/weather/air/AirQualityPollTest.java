package cloud.cholewa.weather.air;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AirQualityPollTest {

    private final AirQuality airQuality = mock(AirQuality.class);
    private final AirQualityPoll sut = new AirQualityPoll(airQuality);

    //one minute, not configurable (HAS-427), and the bound below it
    @Test
    void should_read_the_sensor_every_minute_within_its_bound() throws NoSuchMethodException {
        final Scheduled scheduled = AirQualityPoll.class.getDeclaredMethod("poll").getAnnotation(Scheduled.class);

        assertThat(Duration.parse(scheduled.fixedDelayString())).isEqualTo(Duration.ofMinutes(1));
        assertThat(AirQualityPoll.LIMIT.compareTo(Duration.ofMinutes(1))).isNegative();
    }

    //HAS-304: a read hung on a dead connection ends at its bound
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(airQuality.poll()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::poll)
            .expectSubscription()
            .expectNoEvent(AirQualityPoll.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    //Spring calls the method once and subscribes to the same Mono for every run
    @Test
    void should_build_the_pass_anew_for_every_run() {
        when(airQuality.poll()).thenReturn(Mono.empty());

        final Mono<Void> scheduled = sut.poll();
        scheduled.as(StepVerifier::create).verifyComplete();
        scheduled.as(StepVerifier::create).verifyComplete();

        verify(airQuality, times(2)).poll();
    }
}
