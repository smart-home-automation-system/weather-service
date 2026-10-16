package cloud.cholewa.weather.history;

import cloud.cholewa.weather.api.model.WeatherHistoryReply;
import cloud.cholewa.weather.database.repository.WeatherHistoryRepository;
import cloud.cholewa.weather.error.WeatherError;
import cloud.cholewa.weather.error.WeatherException;
import cloud.cholewa.weather.model.Metric;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeatherHistoryServiceTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);

    private final WeatherHistoryRepository repository = mock(WeatherHistoryRepository.class);
    private final WeatherHistoryService sut = new WeatherHistoryService(repository);

    @Test
    void should_answer_the_metric_in_buckets_chosen_by_the_length_of_the_range() {
        final WeatherHistoryReply.Point point = new WeatherHistoryReply.Point(FROM, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        when(repository.findHistory(Metric.DEW_POINT, FROM, FROM.plusDays(31), 7200)).thenReturn(Flux.just(point));

        sut.queryHistory("dewPoint", FROM, FROM.plusDays(31)).as(StepVerifier::create)
            .assertNext(reply -> {
                assertThat(reply.metric()).isEqualTo("dewPoint");
                assertThat(reply.bucketSeconds()).isEqualTo(7200);
                assertThat(reply.points()).containsExactly(point);
            })
            .verifyComplete();
    }

    @Test
    void should_refuse_a_name_that_is_no_metric_with_its_code() {
        sut.queryHistory("DEW_POINT", FROM, FROM.plusDays(1)).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> {
                assertThat(error).isInstanceOf(WeatherException.class);
                assertThat(((WeatherException) error).getError()).isEqualTo(WeatherError.UNKNOWN_METRIC);
                assertThat(((WeatherException) error).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            });

        verifyNoInteractions(repository);
    }

    @Test
    void should_refuse_a_range_that_breaks_a_rule_without_a_code() {
        sut.queryHistory("wind", FROM, FROM.plusDays(32)).as(StepVerifier::create)
            .verifyErrorSatisfies(error -> assertThat(error).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("31 days"));

        verifyNoInteractions(repository);
    }

    @Test
    void should_answer_a_range_without_readings_with_no_points() {
        when(repository.findHistory(any(), any(), any(), anyLong())).thenReturn(Flux.empty());

        sut.queryHistory("raining", FROM, FROM.plusHours(1)).as(StepVerifier::create)
            .assertNext(reply -> assertThat(reply.points()).isEmpty())
            .verifyComplete();
    }

    //a query that does not answer ends with an error and lets the next one in
    @Test
    void should_cut_off_a_query_that_does_not_answer() {
        when(repository.findHistory(any(), any(), any(), anyLong())).thenReturn(Flux.never());

        StepVerifier.withVirtualTime(() -> sut.queryHistory("wind", FROM, FROM.plusHours(1)))
            .thenAwait(WeatherHistoryService.QUERY_TIMEOUT)
            .verifyError(TimeoutException.class);

        when(repository.findHistory(any(), any(), any(), anyLong())).thenReturn(Flux.empty());
        sut.queryHistory("wind", FROM, FROM.plusHours(1)).as(StepVerifier::create).expectNextCount(1).verifyComplete();
    }
}
