package cloud.cholewa.weather.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

//A request the read API refuses: the status says whose problem it is, the error names the cause, the
//message is answered as details
@Getter
public class WeatherException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final WeatherError error;

    public WeatherException(final HttpStatus httpStatus, final WeatherError error, final String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.error = error;
    }

    public static WeatherException unknownMetric(final String metric) {
        return new WeatherException(HttpStatus.BAD_REQUEST, WeatherError.UNKNOWN_METRIC, "no metric named '" + metric + "'");
    }
}
