package cloud.cholewa.weather.database.model;

import cloud.cholewa.weather.monitor.Watch;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

//A row of station_alert (V2): an alert that is open - what is watched, since when it has been wrong
//(the last sign of life) and when the household was last told
@Table("station_alert")
public record StationAlertEntity(
    @Id
    Long id,
    Watch watch,
    Instant since,
    Instant lastAlertAt
) {
}
