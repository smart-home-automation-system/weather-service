package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.AirSensorRuntimeEntity;
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

//On the table V5 creates, in a PostgreSQL in Docker (Testcontainers), as AirQualityAlertRepositoryTest: the row of a
//sensor stored, found by the sensor, changed in place, one row per sensor. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class AirSensorRuntimeRepositoryTest {

    private static final Instant READ_AT = Instant.parse("2026-11-13T09:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private AirSensorRuntimeRepository sut;

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
    void forgetTheRows() {
        sut.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_the_row_of_a_sensor_and_find_it_by_the_sensor() {
        sut.save(AirSensorRuntimeEntity.builder().sensorId(145).hours(1908).readAt(READ_AT).build())
            .flatMap(stored -> sut.findBySensorId(145).map(found -> found.equals(stored)))
            .as(StepVerifier::create)
            .expectNext(true)
            .verifyComplete();

        sut.findBySensorId(146).as(StepVerifier::create).verifyComplete();
    }

    //another hour and a warning change the row in place
    @Test
    void should_change_the_row_in_place() {
        sut.save(AirSensorRuntimeEntity.builder().sensorId(145).hours(6999).readAt(READ_AT).build())
            .flatMap(stored -> sut.save(stored.toBuilder().hours(7000).readAt(READ_AT.plusSeconds(3600)).warnedAtHours(7000L).build()))
            .then(sut.findBySensorId(145))
            .as(StepVerifier::create)
            .assertNext(found -> {
                assertThat(found.hours()).isEqualTo(7000);
                assertThat(found.readAt()).isEqualTo(READ_AT.plusSeconds(3600));
                assertThat(found.warnedAtHours()).isEqualTo(7000L);
            })
            .verifyComplete();
        sut.count().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    //one row per sensor (the unique constraint of V5)
    @Test
    void should_keep_one_row_per_sensor() {
        sut.save(AirSensorRuntimeEntity.builder().sensorId(145).hours(1908).readAt(READ_AT).build())
            .then(sut.save(AirSensorRuntimeEntity.builder().sensorId(145).hours(1909).readAt(READ_AT).build()))
            .as(StepVerifier::create)
            .verifyError(DataIntegrityViolationException.class);
    }
}
