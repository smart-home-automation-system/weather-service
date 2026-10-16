package cloud.cholewa.weather.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * {@code GET /home/weather/status}: how the service hears the station.<br>
 * {@code gateway} - the gateway of the station; absent while the registry has no station.<br>
 * {@code lastReadingAt} - the newest time the gateway heard the station (house time); absent until it
 * was heard since the start.<br>
 * {@code silent} - nothing heard for {@code weather.monitor.silent-after}, the threshold of the
 * alerts; true as well when nothing was heard since the start - the alert about that waits for the
 * threshold, counted from the start of the watch, so the status may show silence before the household
 * is told.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StationStatus(Gateway gateway, LocalDateTime lastReadingAt, boolean silent) {

    /**
     * {@code deviceId} - the id the gateway publishes under.<br>
     * {@code reachable} - whether the last read of its components was answered; absent before the
     * first read.<br>
     * {@code checkedAt} - when that read was made (house time); absent before it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Gateway(String deviceId, Boolean reachable, LocalDateTime checkedAt) {
    }
}
