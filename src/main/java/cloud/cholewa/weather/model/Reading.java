package cloud.cholewa.weather.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//The last value of one metric and when the station measured it (the last_updated_ts of its sensor,
//house time). A number, or a flag for RAIN - never both
public record Reading(BigDecimal number, Boolean flag, LocalDateTime measuredAt) {

    public static Reading number(final BigDecimal number, final LocalDateTime measuredAt) {
        return new Reading(number, null, measuredAt);
    }

    public static Reading flag(final boolean flag, final LocalDateTime measuredAt) {
        return new Reading(null, flag, measuredAt);
    }
}
