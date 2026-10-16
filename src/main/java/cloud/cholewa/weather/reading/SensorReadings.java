package cloud.cholewa.weather.reading;

import cloud.cholewa.shelly.model.BthomeSensorStatus;
import cloud.cholewa.weather.model.Metric;
import cloud.cholewa.weather.model.Reading;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

//The status of a bthomesensor as a reading of its metric - from the status topic of the sensor and
//from the status Shelly.GetComponents lists with it. A flag for RAIN, a number for every other one;
//no value or no time (0 until the gateway has heard the object once) is no reading. A value of the
//wrong type is a sensor the map got wrong - said at WARN, never stored as something else
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SensorReadings {

    public static Optional<Reading> of(final Metric metric, final BthomeSensorStatus status) {
        return of(metric, status, true);
    }

    //warn false: the read of the map, which repeats every hour - the router says it when the sensor publishes
    public static Optional<Reading> of(final Metric metric, final BthomeSensorStatus status, final boolean warn) {
        final Object value = status.getValue();
        final Long timestamp = status.getLastUpdatedTs();
        if (value == null || timestamp == null || timestamp <= 0) {
            return Optional.empty();
        }
        final Instant measuredAt = Instant.ofEpochSecond(timestamp);
        if (metric.isFlag() && value instanceof Boolean flag) {
            return Optional.of(Reading.flag(flag, measuredAt));
        }
        if (!metric.isFlag() && value instanceof Number number) {
            return Optional.of(Reading.number(new BigDecimal(number.toString()), measuredAt));
        }
        if (warn) {
            log.warn("Sensor bthomesensor:{} ({}) sent the value {}, which is not what that metric is",
                status.getId(), metric, value);
        }
        return Optional.empty();
    }
}
