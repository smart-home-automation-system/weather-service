package cloud.cholewa.weather.air;

import lombok.Getter;

//A read of the air sensor that brought no state, by the kind of the failure - the key of the hourly WARN
@Getter
public class AirSensorCallException extends RuntimeException {

    public enum Kind { CONNECTION, TIMEOUT, STATUS, INVALID_BODY }

    private final Kind kind;

    public AirSensorCallException(final Kind kind, final String message) {
        super(message);
        this.kind = kind;
    }
}
