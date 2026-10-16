package cloud.cholewa.weather.database.model;

import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//A row of weather_reading - a snapshot of the current state (V1); a metric not received is null
@Builder
@Table("weather_reading")
public record WeatherReadingEntity(
    @Id
    Long id,
    LocalDateTime measuredAt,
    BigDecimal temperature,
    Integer humidity,
    BigDecimal dewPoint,
    BigDecimal pressure,
    Integer illuminance,
    BigDecimal uv,
    BigDecimal wind,
    BigDecimal gust,
    Integer windDirection,
    Boolean raining,
    BigDecimal precipitation,
    Integer battery,
    BigDecimal voltage
) {
}
