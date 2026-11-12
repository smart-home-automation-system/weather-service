package cloud.cholewa.weather.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the profiles read it, as MonitorPropertiesTest
class AirPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    //HAS-429: the sensor silent for half an hour; smog from the level 5 (bad) held for half an hour, clear again at 3
    @Test
    void should_watch_the_sensor_and_the_smog_with_the_defaults() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final AirProperties properties = context.getBean(AirProperties.class);

                assertThat(properties.silentAfter()).isEqualTo(Duration.ofMinutes(30));
                assertThat(properties.smogLevel()).isEqualTo(5);
                assertThat(properties.clearLevel()).isEqualTo(3);
                assertThat(properties.smogAfter()).isEqualTo(Duration.ofMinutes(30));
            });
    }

    //without the unit a bare number would bind as milliseconds
    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "weather.air.silent-after=45", "weather.air.smog-after=20")
            .run(context -> {
                final AirProperties properties = context.getBean(AirProperties.class);

                assertThat(properties.silentAfter()).isEqualTo(Duration.ofMinutes(45));
                assertThat(properties.smogAfter()).isEqualTo(Duration.ofMinutes(20));
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "weather.air.silent-after=9m",
        "weather.air.silent-after=1801",
        "weather.air.smog-after=4m",
        "weather.air.smog-after=361",
        "weather.air.smog-level=1",
        "weather.air.smog-level=7",
        "weather.air.clear-level=0",
        "weather.air.clear-level=5"
    })
    void should_refuse_a_value_out_of_its_bounds(final String property) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", property)
            .run(context -> assertThat(context).hasFailed());
    }

    //the clear level is below the smog level, or an episode could never close
    @Test
    void should_refuse_a_clear_level_not_below_the_smog_level() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "weather.air.smog-level=3", "weather.air.clear-level=3")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AirProperties.class)
    static class Properties {
    }
}
