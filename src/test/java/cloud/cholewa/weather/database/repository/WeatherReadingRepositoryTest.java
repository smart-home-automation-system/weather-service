package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//On the table the migration of the service creates, in a PostgreSQL in Docker (Testcontainers): the
//migration itself is what is tested here as much as the statements - the columns, their scales and
//the identity. The slice builds a plain connection from spring.r2dbc.*, not the pooled one of
//cholewa-commons, which is why the container needs no SSL here. Without a Docker the test fails, it
//is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class WeatherReadingRepositoryTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 9, 21, 28);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private WeatherReadingRepository sut;

    @DynamicPropertySource
    static void connectToTheContainer(final DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://%s:%d/%s".formatted(
            POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    //the test profile switches Flyway off and the slice would not run it anyway
    @BeforeAll
    static void migrate() {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .load()
            .migrate();
    }

    @BeforeEach
    void forgetTheReadings() {
        sut.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_a_whole_reading_as_it_was_taken() {
        final WeatherReadingEntity reading = new WeatherReadingEntity(null, AT, new BigDecimal("4.4"), 98,
            new BigDecimal("4.11"), new BigDecimal("993.30"), 3310, new BigDecimal("0.0"), new BigDecimal("1.10"),
            new BigDecimal("1.70"), 173, true, new BigDecimal("4311.0"), 100, new BigDecimal("4.20"));

        final WeatherReadingEntity saved = sut.save(reading).block();

        StepVerifier.create(sut.findById(saved.id()))
            .assertNext(read -> assertThat(read).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .ignoringFields("id")
                .isEqualTo(reading))
            .verifyComplete();
    }

    @Test
    void should_store_a_reading_of_one_metric() {
        StepVerifier.create(sut.save(WeatherReadingEntity.builder().measuredAt(AT).raining(false).build()))
            .assertNext(saved -> {
                assertThat(saved.id()).isNotNull();
                assertThat(saved.temperature()).isNull();
            })
            .verifyComplete();
    }

    //the latest is the highest id, not the latest measured_at: the night the clocks go back a later
    //row may be measured "earlier"
    @Test
    void should_read_the_latest_row_by_its_id() {
        sut.save(WeatherReadingEntity.builder().measuredAt(AT).temperature(new BigDecimal("4.4")).build()).block();
        sut.save(WeatherReadingEntity.builder().measuredAt(AT.minusHours(1)).temperature(new BigDecimal("3.9")).build()).block();

        StepVerifier.create(sut.findFirstByOrderByIdDesc())
            .assertNext(latest -> assertThat(latest.temperature()).isEqualByComparingTo("3.9"))
            .verifyComplete();
    }

    @Test
    void should_read_nothing_from_an_empty_table() {
        StepVerifier.create(sut.findFirstByOrderByIdDesc()).verifyComplete();
    }
}
