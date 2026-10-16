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
 * {@code weather.retention} - how long the rows of weather_reading are kept; older ones are deleted
 * every night (WeatherRetention).<br>
 * A bare number is days: without the unit it would bind as milliseconds and the first night would
 * delete the whole history - hence the lower bound of 30 days, a month of the charts. The upper bound
 * of ten years keeps a value meant in seconds from keeping everything forever.
 */
@Validated
@ConfigurationProperties(prefix = "weather")
public record RetentionProperties(
    @NotNull @DurationMin(days = 30) @DurationMax(days = 3650) @DurationUnit(ChronoUnit.DAYS)
    @DefaultValue("730d") Duration retention
) {
}
