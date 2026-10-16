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
class MonitorPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(Properties.class);

    @Test
    void should_alert_after_half_an_hour_and_remind_every_hour() {
        contextRunner
            .withPropertyValues("spring.profiles.active=home")
            .run(context -> {
                final MonitorProperties properties = context.getBean(MonitorProperties.class);

                assertThat(properties.silentAfter()).isEqualTo(Duration.ofMinutes(30));
                assertThat(properties.reminderInterval()).isEqualTo(Duration.ofHours(1));
            });
    }

    //without the unit a bare number would bind as milliseconds and the station would be reported at
    //every pass
    @Test
    void should_read_a_bare_number_as_minutes() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=home",
                "weather.monitor.silent-after=45",
                "weather.monitor.reminder-interval=120"
            )
            .run(context -> {
                final MonitorProperties properties = context.getBean(MonitorProperties.class);

                assertThat(properties.silentAfter()).isEqualTo(Duration.ofMinutes(45));
                assertThat(properties.reminderInterval()).isEqualTo(Duration.ofHours(2));
            });
    }

    //below: one failed read of the gateway would be an alert, the reminders a flood; above: a value
    //meant in seconds
    @ParameterizedTest
    @ValueSource(strings = {
        "weather.monitor.silent-after=9m",
        "weather.monitor.silent-after=1801",
        "weather.monitor.reminder-interval=14m",
        "weather.monitor.reminder-interval=8d"
    })
    void should_not_start_with_a_duration_out_of_bounds(final String duration) {
        contextRunner
            .withPropertyValues("spring.profiles.active=home", duration)
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(MonitorProperties.class)
    static class Properties {
    }
}
