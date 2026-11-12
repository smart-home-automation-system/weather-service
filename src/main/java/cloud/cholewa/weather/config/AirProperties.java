package cloud.cholewa.weather.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * The watch over the air quality sensor and the smog outside the house (HAS-429).<br>
 * {@code silentAfter} - how long the sensor may give no valid reading before the technical channel is
 * told (AirSensorWatch); its reminders come every {@code weather.monitor.reminder-interval}.<br>
 * {@code smogLevel} - the level of the air (the GIOS scale 1 - 6) from which it is smog: 5 is bad.<br>
 * {@code clearLevel} - the level up to which it is clear again: 3 is moderate - below the smog level,
 * so the levels between hold the state (hysteresis).<br>
 * {@code smogAfter} - how long a level has to hold before an episode opens or closes (SmogWatch).<br>
 * A bare number of a duration is minutes: without the unit it would bind as milliseconds - the sensor
 * reported at every pass, an episode opened at the first bad reading.
 */
@Validated
@ConfigurationProperties(prefix = "weather.air")
public record AirProperties(
    @NotNull @DurationMin(minutes = 10) @DurationMax(hours = 24) @DurationUnit(ChronoUnit.MINUTES)
    @DefaultValue("30m") Duration silentAfter,
    @NotNull @Min(2) @Max(6) @DefaultValue("5") Integer smogLevel,
    @NotNull @Min(1) @Max(5) @DefaultValue("3") Integer clearLevel,
    @NotNull @DurationMin(minutes = 5) @DurationMax(hours = 6) @DurationUnit(ChronoUnit.MINUTES)
    @DefaultValue("30m") Duration smogAfter
) {

    @AssertTrue(message = "weather.air.clear-level must be below weather.air.smog-level")
    public boolean isClearBelowSmog() {
        return clearLevel == null || smogLevel == null || clearLevel < smogLevel;
    }
}
