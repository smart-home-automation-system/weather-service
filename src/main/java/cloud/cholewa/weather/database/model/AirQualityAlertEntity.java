package cloud.cholewa.weather.database.model;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of air_quality_alert (V4): an episode of smog - of which sensor (its id in the registry), since when,
//when it ended (null while open), its highest level and its highest particles
@Builder(toBuilder = true)
@Table("air_quality_alert")
public record AirQualityAlertEntity(
    @Id
    Long id,
    long sensorId,
    Instant since,
    Instant endedAt,
    int level,
    Integer peakPm10,
    Integer peakPm25
) {
}
