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

class LaserRuntimePollTest {

    private final LaserRuntime laserRuntime = mock(LaserRuntime.class);
    private final LaserRuntimePoll sut = new LaserRuntimePoll(laserRuntime);

    //every five minutes - LaserRuntime asks the sensor once an hour - and the bound below that
    @Test
    void should_look_every_five_minutes_within_its_bound() throws NoSuchMethodException {
        final Scheduled scheduled = LaserRuntimePoll.class.getDeclaredMethod("poll").getAnnotation(Scheduled.class);

        assertThat(Duration.parse(scheduled.fixedDelayString())).isEqualTo(Duration.ofMinutes(5));
        final Duration bound = LaserRuntimePoll.LIMIT;
        assertThat(bound).isLessThan(Duration.ofMinutes(1));
    }

    //HAS-304: a read hung on a dead connection ends at its bound
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        when(laserRuntime.check()).thenReturn(Mono.never());

        StepVerifier.withVirtualTime(sut::poll)
            .expectSubscription()
            .expectNoEvent(LaserRuntimePoll.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();
    }

    //Spring calls the method once and subscribes to the same Mono for every run
    @Test
    void should_build_the_pass_anew_for_every_run() {
        when(laserRuntime.check()).thenReturn(Mono.empty());

        final Mono<Void> scheduled = sut.poll();
        scheduled.as(StepVerifier::create).verifyComplete();
        scheduled.as(StepVerifier::create).verifyComplete();

        verify(laserRuntime, times(2)).check();
    }
}
