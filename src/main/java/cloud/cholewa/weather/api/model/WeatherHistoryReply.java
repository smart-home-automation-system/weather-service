package cloud.cholewa.weather.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code GET /home/weather/history}: one metric over a range, in buckets of one width - the rules of
 * the hot-water history (HAS-200).<br>
 * {@code metric} - the name of the metric as it was asked for.<br>
 * {@code from}, {@code to} - the range as it was asked for; it includes its start and not its end.<br>
 * {@code bucketSeconds} - the width of a bucket, chosen by the service from the length of the range.<br>
 * {@code points} - one per bucket that has a reading, oldest first; a bucket without a reading has no
 * point, so two points further apart than {@code bucketSeconds} are a gap.<br>
 * Every date-time is wall-clock time of the house, as the readings are stored. So on the night the
 * summer time begins the history has a gap of an hour that no sensor caused, and on the night it ends
 * the readings of the hour that happens twice fall into the same buckets.
 */
public record WeatherHistoryReply(
    String metric,
    LocalDateTime from,
    LocalDateTime to,
    long bucketSeconds,
    List<Point> points
) {

    /**
     * {@code at} - the start of the bucket, aligned to the clock of the house, not to the range.<br>
     * {@code avg}, {@code min}, {@code max} - over the stored readings of the bucket within the range,
     * the average rounded to 2 decimals. For {@code raining} a reading counts as 1 when it rained and
     * 0 when not: the average is the share of the readings with rain. For {@code windDirection} the
     * average is the circular mean (350 and 10 average to 0) and there is no {@code min} or {@code max}.
     * For {@code precipitation}, a running total of the station, {@code max - min} is the rain of the
     * bucket.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Point(LocalDateTime at, BigDecimal avg, BigDecimal min, BigDecimal max) {
    }
}
