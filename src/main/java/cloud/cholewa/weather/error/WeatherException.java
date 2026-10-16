package cloud.cholewa.weather.error;

import cloud.cholewa.weather.model.Metric;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.stream.Collectors;

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

    //the names there are, not the one asked for: what a client sent is never echoed into an answer or a log
    public static WeatherException unknownMetric() {
        return new WeatherException(HttpStatus.BAD_REQUEST, WeatherError.UNKNOWN_METRIC, "metric must be one of: "
            + Arrays.stream(Metric.values()).map(Metric::apiName).collect(Collectors.joining(", ")));
    }
}
