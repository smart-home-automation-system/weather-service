package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.database.model.AirQualityAlertEntity;
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

//On the table V4 creates, in a PostgreSQL in Docker (Testcontainers), as StationAlertRepositoryTest: an episode
//stored as it was opened, the open one found, one open at a time. Without a Docker the test fails
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
class AirQualityAlertRepositoryTest {

    private static final Instant SINCE = Instant.parse("2026-11-12T17:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private AirQualityAlertRepository sut;

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
    void forgetTheEpisodes() {
        sut.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_store_an_episode_and_find_it_while_it_is_open() {
        final AirQualityAlertEntity episode = sut.save(AirQualityAlertEntity.builder()
            .sensorId(145).since(SINCE).level(5).peakPm10(110).peakPm25(75).build()).block();

        sut.findFirstByEndedAtIsNull().as(StepVerifier::create)
            .assertNext(found -> assertThat(found).isEqualTo(episode))
            .verifyComplete();
    }

    //an ended episode stays as history and is no longer the open one
    @Test
    void should_find_no_open_episode_after_it_ended() {
        final AirQualityAlertEntity episode = sut.save(AirQualityAlertEntity.builder()
            .sensorId(145).since(SINCE).level(5).build()).block();
        sut.save(episode.toBuilder().endedAt(SINCE.plusSeconds(3600)).build()).block();

        sut.findFirstByEndedAtIsNull().as(StepVerifier::create).verifyComplete();
        sut.count().as(StepVerifier::create).expectNext(1L).verifyComplete();
    }

    //one episode open at a time (the unique index of V4); ended ones may be many
    @Test
    void should_keep_one_open_episode() {
        sut.save(AirQualityAlertEntity.builder().sensorId(145).since(SINCE).level(5).endedAt(SINCE.plusSeconds(60)).build()).block();
        sut.save(AirQualityAlertEntity.builder().sensorId(145).since(SINCE.plusSeconds(120)).level(5)
            .endedAt(SINCE.plusSeconds(180)).build()).block();
        sut.save(AirQualityAlertEntity.builder().sensorId(145).since(SINCE.plusSeconds(240)).level(5).build()).block();

        sut.save(AirQualityAlertEntity.builder().sensorId(145).since(SINCE.plusSeconds(300)).level(6).build())
            .as(StepVerifier::create)
            .verifyError(DataIntegrityViolationException.class);
    }
}
