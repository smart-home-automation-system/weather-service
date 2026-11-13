package cloud.cholewa.weather.air;

import java.math.BigDecimal;
import java.util.Optional;

//What GET /api/air/runtime of a BleBox airSensor answers (HAS-437) - the wire, kept apart from the hours it
//becomes. Read on 2026-11-12: {"runtime":{"timeH":1908}} - the hours the laser of the measuring system has run.
//timeH is read as a decimal so that 1908.5 is refused instead of cut to 1908; what is not there is null
public record AirRuntimeResponse(Runtime runtime) {

    public record Runtime(BigDecimal timeH) {
    }

    //the hours as a whole non-negative number; empty for anything else - {}, a fraction, a negative, too large
    public Optional<Long> hours() {
        return Optional.ofNullable(runtime)
            .map(Runtime::timeH)
            .filter(hours -> hours.signum() >= 0)
            .flatMap(AirRuntimeResponse::whole);
    }

    private static Optional<Long> whole(final BigDecimal hours) {
        try {
            return Optional.of(hours.longValueExact());
        } catch (final ArithmeticException notWhole) {
            return Optional.empty();
        }
    }
}
