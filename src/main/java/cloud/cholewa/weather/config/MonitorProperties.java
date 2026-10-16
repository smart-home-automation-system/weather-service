package cloud.cholewa.weather.config;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * The watch over the weather station and its gateway (StationWatch).<br>
 * {@code silentAfter} - how long the station may send no reading, or the gateway leave the read of
 * its components unanswered, before the household is told.<br>
 * {@code reminderInterval} - how often the alert is repeated while that lasts.<br>
 * A bare number is minutes: without the unit it would bind as milliseconds and the station would be
 * reported at every pass. The lower bounds keep one failed read of the gateway (every five minutes)
 * from being an alert and the reminders from flooding the channel; the upper bounds keep a value
 * meant in seconds from switching the watch off in practice.
 */
@Validated
@ConfigurationProperties(prefix = "weather.monitor")
public record MonitorProperties(
    @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    @DefaultValue("30m") Duration silentAfter,
    @NotNull @DurationMin(minutes = 15) @DurationMax(days = 7) @DurationUnit(ChronoUnit.MINUTES)
    @DefaultValue("1h") Duration reminderInterval
) {
}
