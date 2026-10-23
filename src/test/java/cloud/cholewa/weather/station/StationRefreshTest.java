package cloud.cholewa.weather.station;

import cloud.cholewa.weather.monitor.StationWatch;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationRefreshTest {

    private final WeatherStation weatherStation = mock(WeatherStation.class);
    private final StationWatch stationWatch = mock(StationWatch.class);
    private final StationRefresh sut = new StationRefresh(weatherStation, stationWatch);

    //HAS-304: a read hung on a dead connection ends at its bound, below the five minutes between reads, and
    //the watch judges with the next one
    @Test
    void should_end_a_pass_that_never_completes_at_its_bound() {
        final AtomicBoolean checked = new AtomicBoolean();
        when(weatherStation.refresh()).thenReturn(Mono.never());
        when(stationWatch.check()).thenReturn(Mono.fromRunnable(() -> checked.set(true)));

        StepVerifier.withVirtualTime(sut::refresh)
            .expectSubscription()
            .expectNoEvent(StationRefresh.LIMIT.minusSeconds(1))
            .thenAwait(Duration.ofSeconds(1))
            .verifyComplete();

        assertThat(checked).isFalse();
    }

    //Spring calls the method once and subscribes to the same Mono for every run
    @Test
    void should_build_the_pass_anew_for_every_run() {
        when(weatherStation.refresh()).thenReturn(Mono.empty());
        when(stationWatch.check()).thenReturn(Mono.empty());

        final Mono<Void> scheduled = sut.refresh();
        scheduled.as(StepVerifier::create).verifyComplete();
        scheduled.as(StepVerifier::create).verifyComplete();

        verify(weatherStation, times(2)).refresh();
        verify(stationWatch, times(2)).check();
    }
}
