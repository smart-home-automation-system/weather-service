package cloud.cholewa.weather.history;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

//The rules of the range a history is asked for and the width of the buckets it is answered in - a copy
//of HistoryRange of water-service (HAS-200), which took them from heating-service, so the charts of the
//dashboard need no new client logic. A copy on purpose (fewer than four services, no move to
//cholewa-commons)
final class HistoryRange {

    static final long MAX_DAYS = 31;

    //LocalDateTime holds years the timestamp of the database does not, and a short range out there
    //would pass the rules below and fail in the query, as a 500 - that is the upper bound. The lower
    //one is no limit of the database, only of sense: nothing was stored that long ago
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 9999;

    //A row is stored at most once a minute, after a change: a bucket of 5 minutes holds up to five.
    //Each width divides a day, which is what makes a bucket start on the clock of the house
    private static final Duration SHORT_RANGE = Duration.ofDays(2);
    private static final Duration SHORT_RANGE_BUCKET = Duration.ofMinutes(5);
    private static final Duration MEDIUM_RANGE = Duration.ofDays(8);
    private static final Duration MEDIUM_RANGE_BUCKET = Duration.ofMinutes(30);
    private static final Duration LONG_RANGE_BUCKET = Duration.ofHours(2);

    private HistoryRange() {
    }

    //what is wrong with the range, as the 400 to answer - empty for a valid one
    static Optional<ResponseStatusException> violation(final LocalDateTime from, final LocalDateTime to) {
        if (isOutsideTheCalendar(from) || isOutsideTheCalendar(to)) {
            return Optional.of(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "from and to must be within the years " + MIN_YEAR + " to " + MAX_YEAR
            ));
        }
        if (!from.isBefore(to)) {
            return Optional.of(new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to"));
        }
        if (isLongerThanLimit(from, to)) {
            return Optional.of(new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "The range must not be longer than " + MAX_DAYS + " days"));
        }
        return Optional.empty();
    }

    //The width of the buckets of a valid range: 576, 384 and 372 points at most for a range that
    //starts on a bucket, one more for one that does not - its first bucket starts before "from".
    //The length is counted on the wall clock, as the buckets are - hence the fixed offset, not the
    //zone of the house: two days across a clock change are two days here, not 47 or 49 hours
    static Duration bucket(final LocalDateTime from, final LocalDateTime to) {
        final Duration length = Duration.between(from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC));

        if (length.compareTo(SHORT_RANGE) <= 0) {
            return SHORT_RANGE_BUCKET;
        }
        return length.compareTo(MEDIUM_RANGE) <= 0 ? MEDIUM_RANGE_BUCKET : LONG_RANGE_BUCKET;
    }

    private static boolean isOutsideTheCalendar(final LocalDateTime bound) {
        return bound.getYear() < MIN_YEAR || bound.getYear() > MAX_YEAR;
    }

    //Counted in calendar days on the local dates, with the time of day deciding a range of exactly
    //the limit. Not from.plusDays: the bounds come straight from the request, and adding to a date
    //at the edge of what LocalDateTime holds throws instead of answering 400
    private static boolean isLongerThanLimit(final LocalDateTime from, final LocalDateTime to) {
        final long days = to.toLocalDate().toEpochDay() - from.toLocalDate().toEpochDay();

        return days > MAX_DAYS || (days == MAX_DAYS && to.toLocalTime().isAfter(from.toLocalTime()));
    }
}
