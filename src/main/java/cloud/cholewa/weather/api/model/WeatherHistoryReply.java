package cloud.cholewa.weather.api.model;

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
 * Every date-time is wall-clock time of the house, as the readings are stored.
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
     * 0 when not: the average is the share of the readings with rain.
     */
    public record Point(LocalDateTime at, BigDecimal avg, BigDecimal min, BigDecimal max) {
    }
}
