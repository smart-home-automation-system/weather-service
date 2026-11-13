package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.AirSensorRuntimeEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface AirSensorRuntimeRepository extends R2dbcRepository<AirSensorRuntimeEntity, Long> {

    //the row of the sensor - at most one, by the unique constraint of V5
    Mono<AirSensorRuntimeEntity> findBySensorId(long sensorId);
}
