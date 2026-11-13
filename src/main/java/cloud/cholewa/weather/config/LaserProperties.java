package cloud.cholewa.weather.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The laser of the air quality sensor (HAS-437) - BleBox gives its measuring system about 8000 hours, less
 * outdoors.<br>
 * {@code lifeHours} - the life of the laser in hours, what {@code /status} shows beside the hours it has run.<br>
 * {@code warnAtHours} - the hours from which the technical channel is told to order a new sensor (LaserRuntime),
 * again every 500 hours more; below the life, or the warning would come after the end.
 */
@Validated
@ConfigurationProperties(prefix = "weather.air.laser")
public record LaserProperties(
    @NotNull @Min(1000) @Max(100_000) @DefaultValue("8000") Long lifeHours,
    @NotNull @Min(500) @Max(100_000) @DefaultValue("7000") Long warnAtHours
) {

    @AssertTrue(message = "weather.air.laser.warn-at-hours must be below weather.air.laser.life-hours")
    public boolean isWarnBelowLife() {
        return lifeHours == null || warnAtHours == null || warnAtHours < lifeHours;
    }
}
