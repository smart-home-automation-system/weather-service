package cloud.cholewa.weather.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

//The last value of one metric and when the station measured it (the last_updated_ts of its sensor).
//An instant, not house time: the night the clocks go back house time runs an hour backwards, and a
//reading compared by it would read as older than the one held. A number, or a flag for RAIN - never both
public record Reading(BigDecimal number, Boolean flag, Instant measuredAt) {

    public static Reading number(final BigDecimal number, final Instant measuredAt) {
        return new Reading(number, null, measuredAt);
    }

    public static Reading flag(final boolean flag, final Instant measuredAt) {
        return new Reading(null, flag, measuredAt);
    }

    //the same value measured at the same time - a status the gateway repeated, or a redelivery
    public boolean sameAs(final Reading other) {
        return other != null
            && measuredAt.equals(other.measuredAt)
            && (number == null ? other.number == null : other.number != null && number.compareTo(other.number) == 0)
            && Objects.equals(flag, other.flag);
    }
}
