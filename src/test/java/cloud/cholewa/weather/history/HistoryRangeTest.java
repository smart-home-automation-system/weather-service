package cloud.cholewa.weather.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//a copy of HistoryRangeTest of water-service, as HistoryRange is a copy
class HistoryRangeTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);

    @ParameterizedTest
    @CsvSource({
        "2026-10-01T00:00:00, 2026-10-01T00:00:01",
        "2026-10-01T00:00:00, 2026-10-02T00:00:00",
        //exactly the limit, to the second
        "2026-10-01T00:00:00, 2026-11-01T00:00:00",
        "2026-10-01T13:15:00, 2026-11-01T13:15:00",
        //the whole range in the future: valid, there are simply no readings there
        "2999-01-01T00:00:00, 2999-01-02T00:00:00"
    })
    void should_accept_a_range_that_runs_forward_within_the_limit(final LocalDateTime from, final LocalDateTime to) {
        assertThat(HistoryRange.violation(from, to)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-01T00:00:00, 2026-10-01T00:00:00",
        "2026-10-02T00:00:00, 2026-10-01T00:00:00"
    })
    void should_refuse_a_range_that_does_not_run_forward(final LocalDateTime from, final LocalDateTime to) {
        assertThat(HistoryRange.violation(from, to)).hasValueSatisfying(violation -> {
            assertThat(violation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(violation.getReason()).isEqualTo("from must be before to");
        });
    }

    @ParameterizedTest
    @CsvSource({
        //a second over the limit
        "2026-10-01T00:00:00, 2026-11-01T00:00:01",
        "2026-10-01T00:00:00, 2026-11-02T00:00:00",
        "2026-10-01T00:00:00, 2027-10-01T00:00:00"
    })
    void should_refuse_a_range_longer_than_the_limit(final LocalDateTime from, final LocalDateTime to) {
        assertThat(HistoryRange.violation(from, to)).hasValueSatisfying(violation -> {
            assertThat(violation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(violation.getReason()).isEqualTo("The range must not be longer than 31 days");
        });
    }

    //the bounds come straight from the request: the edge of what LocalDateTime holds is a 400, not
    //an exception of the arithmetic
    @Test
    void should_refuse_the_widest_range_there_is_without_failing() {
        assertThat(HistoryRange.violation(LocalDateTime.MIN, LocalDateTime.MAX)).isPresent();
    }

    //a day in a year the timestamp of the database cannot hold: short and running forward, so
    //without this rule it would reach the query and come back as a 500
    @ParameterizedTest
    @CsvSource({
        "+999999999-12-30T00:00:00, +999999999-12-31T00:00:00",
        "+10000-01-01T00:00:00, +10000-01-02T00:00:00",
        "1999-12-31T00:00:00, 2000-01-01T00:00:00",
        "-4800-01-01T00:00:00, -4800-01-02T00:00:00"
    })
    void should_refuse_a_range_outside_the_years_the_service_answers(final LocalDateTime from, final LocalDateTime to) {
        assertThat(HistoryRange.violation(from, to)).hasValueSatisfying(violation -> {
            assertThat(violation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(violation.getReason()).isEqualTo("from and to must be within the years 2000 to 9999");
        });
    }

    @Test
    void should_accept_the_first_and_the_last_year() {
        assertThat(HistoryRange.violation(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.of(2000, 1, 2, 0, 0)))
            .isEmpty();
        assertThat(HistoryRange.violation(LocalDateTime.of(9999, 12, 30, 0, 0), LocalDateTime.of(9999, 12, 31, 0, 0)))
            .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "PT1S, PT5M",
        "PT24H, PT5M",
        "P2D, PT5M",
        "P2DT1S, PT30M",
        "P7D, PT30M",
        "P8D, PT30M",
        "P8DT1S, PT2H",
        "P30D, PT2H",
        "P31D, PT2H"
    })
    void should_widen_the_bucket_with_the_range(final Duration length, final Duration bucket) {
        assertThat(HistoryRange.bucket(FROM, FROM.plus(length))).isEqualTo(bucket);
    }

    //the width is picked on the wall clock: the two days around the night the summer time ends
    //are 49 hours of real time and still two days of 5-minute buckets
    @Test
    void should_count_the_length_of_a_range_across_a_clock_change_on_the_wall_clock() {
        final LocalDateTime dayBefore = LocalDateTime.of(2026, 10, 24, 0, 0);

        assertThat(HistoryRange.bucket(dayBefore, dayBefore.plusDays(2))).isEqualTo(Duration.ofMinutes(5));
    }

    //A bucket that divides a day starts on the clock of the house in every day of the range, no
    //range answers more points than a chart can draw (one more when it starts inside a bucket),
    //and no bucket is narrower than a minute, the most often a row is stored
    @ParameterizedTest
    @CsvSource({"P2D, 576", "P8D, 384", "P31D, 372"})
    void should_keep_the_points_few_and_the_buckets_aligned(final Duration length, final long points) {
        final Duration bucket = HistoryRange.bucket(FROM, FROM.plus(length));

        assertThat(length.dividedBy(bucket)).isEqualTo(points);
        assertThat(Duration.ofDays(1).toSeconds() % bucket.toSeconds()).isZero();
        assertThat(bucket).isGreaterThan(Duration.ofMinutes(1));
    }
}
