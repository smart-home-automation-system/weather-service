package cloud.cholewa.weather.database.repository;

import cloud.cholewa.weather.api.model.WeatherHistoryReply;
import cloud.cholewa.weather.model.Metric;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//One metric of weather_reading from "from" up to, not including, "to", in buckets of "bucketSeconds",
//oldest first - the query of the hot-water history of water-service. measured_at is the wall-clock time
//of the house without a zone, and the epoch of such a value counts from its own midnight, so a bucket
//that divides a day starts on the clock of the house (00:00, 00:05, ...), whatever "from" is. A row
//without the metric (NULL - never received, or a value that did not fit) is no reading of it, so a
//bucket of such rows only has no point. The database aggregates: a month is up to 44 000 rows, read
//through the index on measured_at (V1) and answered as 372.
//The wind direction is an angle: its average is the circular mean (the direction of the mean of the unit
//vectors - 350 and 10 average to 0, not 180), and it has no minimum or maximum - nulls, left out of the
//answer.
//A column cannot be a parameter of a statement, hence the SQL is built here - from Metric only, never
//from the request. PostgreSQL only: WeatherHistoryRepositoryTest runs it in Docker
@Repository
@RequiredArgsConstructor
public class WeatherHistoryRepository {

    private static final String HISTORY = """
        SELECT TIMESTAMP 'epoch'
                   + floor(extract(epoch FROM measured_at) / :bucketSeconds) * :bucketSeconds * INTERVAL '1 second' AS at,
               %1$s AS avg,
               %2$s AS min,
               %3$s AS max
        FROM weather_reading
        WHERE measured_at >= :from
          AND measured_at < :to
          AND %4$s IS NOT NULL
        GROUP BY at
        ORDER BY at
        """;

    //0 up to 360, rounded to 2 decimals before the modulo (a mean a hair below 0 is 0, not 360.00);
    //atan2 of two zeros (opposite directions in equal parts) is 0
    private static final String CIRCULAR_MEAN =
        "(round((degrees(atan2(avg(sin(radians(%1$s))), avg(cos(radians(%1$s))))) + 360)::numeric, 2) %% 360)";

    private final DatabaseClient databaseClient;

    public Flux<WeatherHistoryReply.Point> findHistory(
        final Metric metric,
        final LocalDateTime from,
        final LocalDateTime to,
        final long bucketSeconds
    ) {
        final String value = value(metric);
        final String sql = metric.isAngle()
            ? HISTORY.formatted(CIRCULAR_MEAN.formatted(metric.column()), "NULL::numeric", "NULL::numeric", metric.column())
            : HISTORY.formatted("round(avg(%s), 2)".formatted(value), "min(%s)".formatted(value), "max(%s)".formatted(value),
                metric.column());
        return databaseClient.sql(sql)
            .bind("bucketSeconds", bucketSeconds)
            .bind("from", from)
            .bind("to", to)
            .map(row -> new WeatherHistoryReply.Point(
                row.get("at", LocalDateTime.class),
                row.get("avg", BigDecimal.class),
                row.get("min", BigDecimal.class),
                row.get("max", BigDecimal.class)))
            .all();
    }

    //as numeric, whatever the type of the column: the codec reads the three aggregates as BigDecimal.
    //The rain is a flag - 1 when it rained, 0 when not
    private static String value(final Metric metric) {
        return metric.isFlag()
            ? "(CASE WHEN %s THEN 1 ELSE 0 END)::numeric".formatted(metric.column())
            : metric.column() + "::numeric";
    }
}
