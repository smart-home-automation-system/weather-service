package cloud.cholewa.weather.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

//What the service schedules: the read of the station from the registry and the gateway with the
//watch over both (StationRefresh) and the snapshot of the readings (WeatherSnapshot); to come, the
//nightly retention of the readings (HAS-238). Off under the test profile: a @SpringBootTest context
//would otherwise run what the application schedules
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
