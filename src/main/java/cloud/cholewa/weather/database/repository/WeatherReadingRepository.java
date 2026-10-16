package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface WeatherReadingRepository extends R2dbcRepository<WeatherReadingEntity, Long> {

    //the row the service starts from. "Latest" is the highest id, not the latest measured_at: rows are
    //inserted in the order they were taken, while measured_at is local time and runs backwards on the
    //night the clocks go back
    Mono<WeatherReadingEntity> findFirstByOrderByIdDesc();

    //the retention: rows measured before "before", the latest row excepted - the state is restored
    //from it, and a station silent for longer than the retention must not start from nothing
    @Modifying
    @Query("DELETE FROM weather_reading WHERE measured_at < :before AND id < (SELECT max(id) FROM weather_reading)")
    Mono<Long> deleteOlderThan(LocalDateTime before);
}
