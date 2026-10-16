package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.StationAlertEntity;
import cloud.cholewa.weather.monitor.Watch;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

//On the table V2 creates, in a PostgreSQL in Docker (Testcontainers), as WeatherReadingRepositoryTest:
//the watch stored by its name, the instants as they were, one row per watch. Without a Docker the test
//fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class StationAlertRepositoryTest {

    private static final Instant SINCE = Instant.parse("2026-10-25T00:58:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private StationAlertRepository sut;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void forgetTheAlerts() {
        sut.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    //the night the clocks go back: an instant stays the instant it was
    @Test
    void should_store_an_alert_as_it_was_raised() {
        final StationAlertEntity alert = new StationAlertEntity(null, 55L, Watch.GATEWAY, SINCE, SINCE.plusSeconds(1800));

        final StationAlertEntity saved = sut.save(alert).block();

        sut.findAll().as(StepVerifier::create)
            .assertNext(read -> {
                assertThat(read.id()).isEqualTo(saved.id());
                assertThat(read.stationId()).isEqualTo(55L);
                assertThat(read.watch()).isEqualTo(Watch.GATEWAY);
                assertThat(read.since()).isEqualTo(SINCE);
                assertThat(read.lastAlertAt()).isEqualTo(SINCE.plusSeconds(1800));
            })
            .verifyComplete();
    }

    @Test
    void should_keep_one_alert_per_watch() {
        sut.save(new StationAlertEntity(null, 55L, Watch.STATION, SINCE, SINCE)).block();

        sut.save(new StationAlertEntity(null, 55L, Watch.STATION, SINCE, SINCE)).as(StepVerifier::create)
            .verifyError(DataIntegrityViolationException.class);
    }
}
