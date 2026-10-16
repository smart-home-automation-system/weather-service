package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.api.model.WeatherHistoryReply;
import cloud.cholewa.weather.database.model.WeatherReadingEntity;
import cloud.cholewa.weather.model.Metric;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

//The history query on the table V1 creates, in a PostgreSQL in Docker (Testcontainers) - the bucketing
//is PostgreSQL SQL. Without a Docker the test fails, it is not skipped
@DataR2dbcTest
@Testcontainers
@ActiveProfiles("test")
@Import(WeatherHistoryRepository.class)
class WeatherHistoryRepositoryTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 16, 0, 0);

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private WeatherHistoryRepository sut;

    @Autowired
    private WeatherReadingRepository readings;

    @Autowired
    private DatabaseClient databaseClient;

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
    void forgetTheReadings() {
        readings.deleteAll().as(StepVerifier::create).verifyComplete();
    }

    @Test
    void should_answer_the_average_the_minimum_and_the_maximum_of_each_bucket() {
        store(DAY.plusMinutes(1), "1.10", null);
        store(DAY.plusMinutes(2), "3.30", null);
        store(DAY.plusMinutes(4), "0.40", null);
        store(DAY.plusMinutes(7), "2.00", null);

        sut.findHistory(Metric.GUST, DAY, DAY.plusDays(1), 300).collectList().as(StepVerifier::create)
            .assertNext(points -> assertThat(points).containsExactly(
                point(DAY, "1.60", "0.40", "3.30"),
                point(DAY.plusMinutes(5), "2.00", "2.00", "2.00")))
            .verifyComplete();
    }

    //a row without the metric is no reading of it: the bucket of such rows only has no point
    @Test
    void should_leave_out_a_bucket_without_a_reading_of_the_metric() {
        store(DAY.plusMinutes(1), "1.10", null);
        store(DAY.plusMinutes(11), null, true);

        sut.findHistory(Metric.GUST, DAY, DAY.plusDays(1), 300).collectList().as(StepVerifier::create)
            .assertNext(points -> assertThat(points).extracting(WeatherHistoryReply.Point::at).containsExactly(DAY))
            .verifyComplete();
    }

    //the start is included, the end is not; a bucket starts on the clock of the house, not on "from"
    @Test
    void should_keep_to_the_range_and_align_the_buckets_to_the_clock() {
        store(DAY.plusMinutes(2), "9.00", null);
        store(DAY.plusMinutes(3), "1.00", null);
        store(DAY.plusMinutes(8), "2.00", null);
        store(DAY.plusMinutes(10), "7.00", null);

        sut.findHistory(Metric.GUST, DAY.plusMinutes(3), DAY.plusMinutes(10), 300).collectList().as(StepVerifier::create)
            .assertNext(points -> assertThat(points).containsExactly(
                point(DAY, "1.00", "1.00", "1.00"),
                point(DAY.plusMinutes(5), "2.00", "2.00", "2.00")))
            .verifyComplete();
    }

    //the rain is a flag: 1 when it rained, 0 when not - the average is the share of the rows with rain
    @Test
    void should_answer_the_rain_as_the_share_of_the_readings_with_rain() {
        store(DAY.plusMinutes(1), null, true);
        store(DAY.plusMinutes(2), null, false);
        store(DAY.plusMinutes(3), null, false);
        store(DAY.plusMinutes(4), null, false);

        sut.findHistory(Metric.RAIN, DAY, DAY.plusDays(1), 300).collectList().as(StepVerifier::create)
            .assertNext(points -> assertThat(points).containsExactly(point(DAY, "0.25", "0", "1")))
            .verifyComplete();
    }

    //a whole-number column answers as a number like the others
    @Test
    void should_answer_a_whole_number_column() {
        databaseClient.sql("INSERT INTO weather_reading (measured_at, humidity, wind_direction) VALUES (:at, 98, 173)")
            .bind("at", DAY.plusMinutes(1))
            .then().block();

        sut.findHistory(Metric.WIND_DIRECTION, DAY, DAY.plusDays(1), 300).collectList().as(StepVerifier::create)
            .assertNext(points -> assertThat(points).containsExactly(point(DAY, "173.00", "173", "173")))
            .verifyComplete();
    }

    //DoD: a month of history for one metric - a row a minute for 31 days, the most the snapshot stores,
    //answered as 2-hour buckets well within the 500 ms the dashboard may wait (the gateway adds its hop)
    @Test
    void should_answer_a_month_of_readings_quickly() {
        databaseClient.sql("""
                INSERT INTO weather_reading (measured_at, temperature, wind)
                SELECT t, 10 + 5 * sin(extract(epoch FROM t) / 3600), 2
                FROM generate_series(:from, :to, INTERVAL '1 minute') AS t
                """)
            .bind("from", DAY.minusDays(31))
            .bind("to", DAY.minusMinutes(1))
            .then().block();
        //the first query of a connection prepares the statement - the dashboard pays it once
        sut.findHistory(Metric.TEMPERATURE, DAY.minusDays(1), DAY, 300).then().block();

        final long started = System.nanoTime();
        final List<WeatherHistoryReply.Point> points =
            sut.findHistory(Metric.TEMPERATURE, DAY.minusDays(31), DAY, 7200).collectList().block();
        final Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(points).hasSize(372);
        assertThat(took).isLessThan(Duration.ofMillis(500));
    }

    private void store(final LocalDateTime at, final String gust, final Boolean raining) {
        readings.save(WeatherReadingEntity.builder()
                .measuredAt(at)
                .gust(gust == null ? null : new BigDecimal(gust))
                .raining(raining)
                .build())
            .then().block();
    }

    private static WeatherHistoryReply.Point point(final LocalDateTime at, final String avg, final String min, final String max) {
        return new WeatherHistoryReply.Point(at, new BigDecimal(avg), new BigDecimal(min), new BigDecimal(max));
    }
}
