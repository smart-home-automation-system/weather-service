package cloud.cholewa.weather.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

//application.yaml as the profiles read it, as AirPropertiesTest
class LaserPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    //HAS-437: BleBox gives the measuring system about 8000 h; the household is told from 7000 h
    @Test
    void should_warn_a_thousand_hours_before_the_life_of_the_laser() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final LaserProperties properties = context.getBean(LaserProperties.class);

                assertThat(properties.lifeHours()).isEqualTo(8000L);
                assertThat(properties.warnAtHours()).isEqualTo(7000L);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "weather.air.laser.life-hours=999",
        "weather.air.laser.life-hours=100001",
        "weather.air.laser.warn-at-hours=499",
        "weather.air.laser.warn-at-hours=abc"
    })
    void should_refuse_a_value_out_of_its_bounds(final String property) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", property)
            .run(context -> assertThat(context).hasFailed());
    }

    //the warning comes before the end of the life, or it would come too late
    @Test
    void should_refuse_a_warning_not_below_the_life() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "weather.air.laser.life-hours=7000")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LaserProperties.class)
    static class Properties {
    }
}
