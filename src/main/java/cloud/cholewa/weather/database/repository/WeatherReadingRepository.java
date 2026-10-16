package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface WeatherReadingRepository extends R2dbcRepository<WeatherReadingEntity, Long> {

    //the row the service starts from. "Latest" is the highest id, not the latest measured_at: rows are
    //inserted in the order they were taken, while measured_at is local time and runs backwards on the
    //night the clocks go back
    Mono<WeatherReadingEntity> findFirstByOrderByIdDesc();
}
