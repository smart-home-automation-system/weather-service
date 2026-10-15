package cloud.cholewa.weather;

import cloud.cholewa.weather.config.InternalServiceProperties;
import cloud.cholewa.weather.config.NotificationConnectionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({
    NotificationConnectionProperties.class,
    InternalServiceProperties.class
})
public class WeatherServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeatherServiceApplication.class, args);
    }
}
