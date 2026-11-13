package cloud.cholewa.weather;

import cloud.cholewa.weather.config.AirProperties;
import cloud.cholewa.weather.config.InternalServiceProperties;
import cloud.cholewa.weather.config.LaserProperties;
import cloud.cholewa.weather.config.MonitorProperties;
import cloud.cholewa.weather.config.NotificationConnectionProperties;
import cloud.cholewa.weather.config.RetentionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    NotificationConnectionProperties.class,
    InternalServiceProperties.class,
    MonitorProperties.class,
    RetentionProperties.class,
    AirProperties.class,
    LaserProperties.class
})
public class WeatherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherServiceApplication.class, args);
    }
}
