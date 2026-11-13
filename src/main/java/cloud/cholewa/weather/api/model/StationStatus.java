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
 * is told.<br>
 * {@code airSensor} - the air quality sensor (HAS-427); absent while the registry has none.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StationStatus(Gateway gateway, LocalDateTime lastReadingAt, boolean silent, AirSensor airSensor) {

    /**
     * {@code deviceId} - the id the gateway publishes under.<br>
     * {@code reachable} - whether the last read of its components was answered; absent before the
     * first read.<br>
     * {@code checkedAt} - when that read was made (house time); absent before it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Gateway(String deviceId, Boolean reachable, LocalDateTime checkedAt) {
    }

    /**
     * {@code address} - where the sensor is read.<br>
     * {@code reachable} - whether the last read answered with its state; absent before the first read.<br>
     * {@code checkedAt} - when that read was made (house time); absent before it.<br>
     * {@code lastReadingAt} - the newest time the sensor measured (house time); absent until it has.<br>
     * {@code silent} - nothing valid measured for {@code weather.air.silent-after} (HAS-429), the threshold of its alert;
     * true as well when nothing was measured since the start.<br>
     * {@code smog} - the episode of smog going on; absent while the air is fine.<br>
     * {@code laser} - the hours its laser has run (HAS-437); absent until they were first read.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AirSensor(String address, Boolean reachable, LocalDateTime checkedAt, LocalDateTime lastReadingAt,
                            boolean silent, Smog smog, Laser laser) {
    }

    /**
     * {@code since} - when the level reached the smog level (house time).<br>
     * {@code level} - the highest level of the episode so far.
     */
    public record Smog(LocalDateTime since, int level) {
    }

    /**
     * {@code hours} - the hours the laser of the sensor has run, by its own counter.<br>
     * {@code readAt} - when the counter was read (house time) - once an hour.<br>
     * {@code lifeHours} - the life BleBox gives the laser ({@code weather.air.laser.life-hours}).<br>
     * {@code warn} - the counter has reached {@code weather.air.laser.warn-at-hours}: time to order a new sensor.
     */
    public record Laser(long hours, LocalDateTime readAt, long lifeHours, boolean warn) {
    }
}
