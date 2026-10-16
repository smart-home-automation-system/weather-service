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

//application.yaml as the profiles read it, as InternalServicePropertiesTest
class RetentionPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_keep_the_readings_for_two_years() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> assertThat(context.getBean(RetentionProperties.class).retention()).isEqualTo(Duration.ofDays(730)));
    }

    //without the unit a bare number would bind as milliseconds and the first night would delete everything
    @Test
    void should_read_a_bare_number_as_days() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", "weather.retention=400")
            .run(context -> assertThat(context.getBean(RetentionProperties.class).retention()).isEqualTo(Duration.ofDays(400)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"weather.retention=29", "weather.retention=29d", "weather.retention=PT12H", "weather.retention=3651"})
    void should_not_start_with_a_retention_out_of_bounds(final String retention) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", retention)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(RetentionProperties.class)
    static class Properties {
    }
}
