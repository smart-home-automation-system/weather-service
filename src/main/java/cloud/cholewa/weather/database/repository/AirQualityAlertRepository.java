package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.AirQualityAlertEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface AirQualityAlertRepository extends R2dbcRepository<AirQualityAlertEntity, Long> {

    //the open episode - at most one, by the unique index of V4
    Mono<AirQualityAlertEntity> findFirstByEndedAtIsNull();
}
