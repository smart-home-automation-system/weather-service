package cloud.cholewa.weather.api.model;

import java.time.LocalDateTime;

/**
 * One metric of {@code GET /home/weather/current}.<br>
 * {@code value} - a number, or {@code true}/{@code false} for {@code raining}.<br>
 * {@code measuredAt} - the newest time the station is known to have had this value (house time,
 * without an offset): a value that does not change is confirmed by every packet that carries it.<br>
 * {@code stale} - true when that time is older than {@code weather.monitor.silent-after}.
 * A metric never received is not in the answer at all - never null, never zero.
 */
public record CurrentMetric(Object value, LocalDateTime measuredAt, boolean stale) {
}
