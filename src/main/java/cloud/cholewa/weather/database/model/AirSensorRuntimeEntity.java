package cloud.cholewa.weather.database.model;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of air_sensor_runtime (V5): the hours the laser of a sensor (its id in the registry) has run, the time of
//the read that changed them, and the step of the last warning to order a new sensor (null before the first)
@Builder(toBuilder = true)
@Table("air_sensor_runtime")
public record AirSensorRuntimeEntity(
    @Id
    Long id,
    long sensorId,
    long hours,
    Instant readAt,
    Long warnedAtHours
) {
}
