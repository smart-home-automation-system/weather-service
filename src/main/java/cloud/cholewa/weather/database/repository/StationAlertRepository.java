package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.StationAlertEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface StationAlertRepository extends R2dbcRepository<StationAlertEntity, Long> {
}
